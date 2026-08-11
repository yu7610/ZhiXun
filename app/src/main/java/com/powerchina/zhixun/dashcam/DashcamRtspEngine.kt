package com.powerchina.zhixun.dashcam

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.base.recording.RecordController
import com.pedro.library.rtsp.RtspStream
import com.pedro.library.view.OpenGlView
import com.pedro.rtsp.rtsp.Protocol
import com.powerchina.zhixun.data.ConfigManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一路相机（RootEncoder / Camera2）：预览 + 本地录像 + RTSP 推流共用同一编码链路。
 */
class DashcamRtspEngine(
    private val context: Context,
    private val openGlView: OpenGlView,
) {
    private val tag = TAG
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingStreamStart: Runnable? = null
    private val streamRetryCount = AtomicInteger(0)
    /** 用户主动停录/释放时置位，忽略 Closed selector 类失败 */
    private val stoppingIntentionally = AtomicBoolean(false)

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            Log.i(tag, "RTSP connecting $url")
        }

        override fun onConnectionSuccess() {
            Log.i(tag, "RTSP 推流连接成功")
            streaming.set(true)
            streamRetryCount.set(0)
        }

        override fun onConnectionFailed(reason: String) {
            streaming.set(false)
            if (stoppingIntentionally.get()) {
                Log.i(tag, "RTSP 已主动停止: $reason")
                return
            }
            Log.w(tag, "RTSP 推流失败: $reason")
            // 编码器尚未产出 SPS/PPS：延后重试
            val spsIssue = reason.contains("sps", ignoreCase = true) ||
                reason.contains("pps", ignoreCase = true)
            if (spsIssue && recording.get()) {
                val attempt = streamRetryCount.incrementAndGet()
                if (attempt <= MAX_STREAM_RETRIES) {
                    val delayMs = STREAM_RETRY_BASE_MS * attempt
                    Log.i(tag, "SPS/PPS 未就绪，${delayMs}ms 后重试 ($attempt/$MAX_STREAM_RETRIES)")
                    runCatching { streamer.requestKeyframe() }
                    scheduleLiveStream(delayMs)
                    return
                }
            }
            // 连接阶段失败（非主动停止）：短暂后重试
            val connectIssue = reason.contains("timeout", ignoreCase = true) ||
                reason.contains("configure", ignoreCase = true) ||
                reason.contains("refused", ignoreCase = true) ||
                reason.contains("unreachable", ignoreCase = true)
            if (connectIssue && recording.get()) {
                val attempt = streamRetryCount.incrementAndGet()
                if (attempt <= MAX_STREAM_RETRIES) {
                    val delayMs = STREAM_RETRY_BASE_MS * attempt
                    Log.i(tag, "RTSP 连接失败，${delayMs}ms 后重试 ($attempt/$MAX_STREAM_RETRIES)")
                    scheduleLiveStream(delayMs)
                }
            }
        }

        override fun onNewBitrate(bitrate: Long) = Unit

        override fun onDisconnect() {
            Log.i(tag, "RTSP 推流断开")
            streaming.set(false)
        }

        override fun onAuthError() {
            Log.w(tag, "RTSP 鉴权失败")
            streaming.set(false)
        }

        override fun onAuthSuccess() {
            Log.i(tag, "RTSP 鉴权成功")
        }
    }

    private lateinit var streamer: RtspStream
    private val prepared = AtomicBoolean(false)
    private val streaming = AtomicBoolean(false)
    private val recording = AtomicBoolean(false)

    init {
        streamer = RtspStream(context.applicationContext, connectChecker)
        configureRtspClient()
    }

    private fun configureRtspClient() {
        val client = streamer.getStreamClient()
        if (DashcamRtspConfig.FORCE_TCP) {
            client.setProtocol(Protocol.TCP)
            Log.i(tag, "RTSP 传输：TCP")
        }
        client.setLogs(true)
        client.setReTries(5)
        client.setSocketTimeout(12_000)
    }

    val isRecording: Boolean get() = recording.get() || streamer.isRecording
    val isStreaming: Boolean get() = streaming.get() || streamer.isStreaming

    fun prepareAndStartPreview(lensFacing: Int): Boolean {
        if (!prepared.get()) {
            val videoOk = streamer.prepareVideo(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_BITRATE, VIDEO_FPS)
            val audioOk = streamer.prepareAudio(AUDIO_SAMPLE_RATE, true, AUDIO_BITRATE)
            if (!videoOk) {
                Log.e(tag, "prepareVideo 失败")
                return false
            }
            if (!audioOk) {
                Log.w(tag, "prepareAudio 失败，将无音频录像/推流")
            }
            prepared.set(true)
            // 息屏后 Surface 可能销毁，强制继续渲染以保持录像/推流
            runCatching { openGlView.setForceRender(true) }
        }
        applyLensFacing(lensFacing)
        if (!streamer.isOnPreview) {
            streamer.startPreview(openGlView)
        }
        Log.i(tag, "预览已启动 facing=$lensFacing")
        return true
    }

    fun applyLensFacing(lensFacing: Int) {
        val camera = streamer.videoSource as? Camera2Source ?: return
        val wantFront = lensFacing == CameraSelector.LENS_FACING_FRONT
        val isFront = camera.getCameraFacing() == CameraHelper.Facing.FRONT
        if (wantFront != isFront) {
            runCatching { camera.switchCamera() }
                .onSuccess { Log.i(tag, "切换摄像头 → facing=$lensFacing") }
                .onFailure { Log.w(tag, "切换摄像头失败", it) }
        }
    }

    /**
     * 开始本地录像；有网时延后 startStream（等编码器产出 SPS/PPS）。
     */
    fun startRecording(
        recordFile: File,
        onStarted: () -> Unit,
        onError: (String) -> Unit,
        onStopped: (Result<File>) -> Unit,
    ): Boolean {
        if (recording.get() || streamer.isRecording) {
            Log.w(tag, "已在录像，跳过")
            return false
        }
        if (!prepared.get() && !prepareAndStartPreview(CameraSelector.LENS_FACING_BACK)) {
            onError("相机未就绪")
            return false
        }
        recordFile.parentFile?.mkdirs()
        if (recordFile.exists()) recordFile.delete()
        streamRetryCount.set(0)
        stoppingIntentionally.set(false)
        cancelScheduledStream()

        val listener = object : RecordController.Listener {
            override fun onStatusChange(status: RecordController.Status) {
                when (status) {
                    RecordController.Status.STARTED, RecordController.Status.RECORDING -> {
                        if (recording.compareAndSet(false, true)) {
                            Log.i(tag, "本地录像已开始 ${recordFile.name}")
                            // 等编码器产出关键帧/SPS 后再推流，避免 "sps or pps is null"
                            scheduleLiveStream(STREAM_START_DELAY_MS)
                            onStarted()
                        }
                    }
                    RecordController.Status.STOPPED -> {
                        recording.set(false)
                        stopLiveStreamInternal()
                        if (recordFile.exists() && recordFile.length() > 0L) {
                            Log.i(tag, "本地录像结束 size=${recordFile.length()}B")
                            onStopped(Result.success(recordFile))
                        } else {
                            onStopped(Result.failure(IllegalStateException("录像文件为空")))
                        }
                    }
                    else -> Unit
                }
            }

            override fun onNewBitrate(bitrate: Long) = Unit

            override fun onError(e: Exception) {
                recording.set(false)
                stopLiveStreamInternal()
                Log.e(tag, "录像错误", e)
                onError(e.message ?: "录像失败")
                onStopped(Result.failure(e))
            }
        }

        return runCatching {
            streamer.startRecord(
                recordFile.absolutePath,
                RecordController.RecordTracks.ALL,
                listener,
            )
            true
        }.getOrElse {
            Log.e(tag, "startRecord 失败", it)
            onError(it.message ?: "无法开始录像")
            false
        }
    }

    fun stopRecording() {
        if (!streamer.isRecording && !recording.get()) return
        stoppingIntentionally.set(true)
        cancelScheduledStream()
        runCatching { streamer.stopRecord() }
            .onFailure { Log.w(tag, "stopRecord 异常", it) }
        stopLiveStreamInternal()
        recording.set(false)
    }

    private fun scheduleLiveStream(delayMs: Long) {
        if (!NetworkMonitor.isOnline(context)) {
            Log.i(tag, "无网络，仅本地录像")
            return
        }
        cancelScheduledStream()
        val runnable = Runnable { tryStartLiveStream() }
        pendingStreamStart = runnable
        Log.i(tag, "计划 ${delayMs}ms 后启动 RTSP 推流")
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelScheduledStream() {
        pendingStreamStart?.let { mainHandler.removeCallbacks(it) }
        pendingStreamStart = null
    }

    private fun tryStartLiveStream() {
        pendingStreamStart = null
        if (!recording.get()) {
            Log.i(tag, "已停止录像，取消推流")
            return
        }
        if (streamer.isStreaming) return
        val terCode = ConfigManager(context).loadConfig().macAddress
        val url = DashcamRtspConfig.buildPushUrl(DashcamRtspConfig.deviceStreamName(terCode))
        runCatching {
            streamer.requestKeyframe()
            streamer.startStream(url)
            Log.i(tag, "已启动 RTSP 推流 url=$url")
        }.onFailure {
            Log.w(tag, "startStream 失败（继续本地录像）", it)
        }
    }

    private fun stopLiveStreamInternal() {
        cancelScheduledStream()
        if (!streamer.isStreaming && !streaming.get()) return
        stoppingIntentionally.set(true)
        runCatching { streamer.stopStream() }
            .onFailure { Log.w(tag, "stopStream 异常", it) }
        streaming.set(false)
    }

    fun takePicture(outputFile: File, onResult: (Result<File>) -> Unit) {
        if (!streamer.isOnPreview && !prepared.get()) {
            onResult(Result.failure(IllegalStateException("预览未启动")))
            return
        }
        runCatching {
            openGlView.takePhoto { bitmap ->
                runCatching {
                    outputFile.parentFile?.mkdirs()
                    FileOutputStream(outputFile).use { out ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) {
                            throw IllegalStateException("JPEG 压缩失败")
                        }
                    }
                    if (!bitmap.isRecycled) bitmap.recycle()
                    require(outputFile.exists() && outputFile.length() > 0L) { "拍照文件为空" }
                    onResult(Result.success(outputFile))
                }.onFailure { e ->
                    if (!bitmap.isRecycled) bitmap.recycle()
                    onResult(Result.failure(e))
                }
            }
        }.onFailure { e ->
            onResult(Result.failure(e))
        }
    }

    fun release() {
        cancelScheduledStream()
        runCatching { stopRecording() }
        stopLiveStreamInternal()
        runCatching { if (streamer.isOnPreview) streamer.stopPreview() }
        runCatching { streamer.release() }
        prepared.set(false)
        recording.set(false)
        streaming.set(false)
        Log.i(tag, "engine released")
    }

    companion object {
        const val TAG = "DashcamRtsp"
        private const val VIDEO_WIDTH = 1920
        private const val VIDEO_HEIGHT = 1080
        private const val VIDEO_FPS = 25
        /** 1080p 推荐约 5Mbps */
        private const val VIDEO_BITRATE = 5_000_000
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val AUDIO_BITRATE = 128_000
        /** 开录后稍等编码器产出 SPS/PPS 再连 RTSP */
        private const val STREAM_START_DELAY_MS = 1_500L
        private const val STREAM_RETRY_BASE_MS = 1_200L
        private const val MAX_STREAM_RETRIES = 3
    }
}
