package com.powerchina.zhixun.dashcam

import android.content.Context
import android.net.Uri
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.decoder.AudioDecoderInterface
import com.pedro.encoder.input.decoder.VideoDecoderInterface
import com.pedro.library.rtsp.RtspFromFile
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 将本地 MP4 按文档方式推到 ZLMediaKit（等价于 ffmpeg -re -i file -f rtsp ...）。
 * 在录像上传成功或补传成功后触发；与 CameraX 录像不抢相机。
 */
object DashcamRtspFilePublisher {

    private const val TAG = "DashcamRtsp"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val busy = AtomicBoolean(false)

    fun publishAsync(context: Context, videoFile: File, terCode: String) {
        if (!videoFile.exists() || videoFile.length() == 0L) return
        if (!NetworkMonitor.isOnline(context)) {
            Log.i(TAG, "无网络，跳过 RTSP 文件推流")
            return
        }
        if (!busy.compareAndSet(false, true)) {
            Log.i(TAG, "已有推流任务，跳过 ${videoFile.name}")
            return
        }
        val url = DashcamRtspConfig.buildPushUrl(DashcamRtspConfig.deviceStreamName(terCode))
        Log.i(TAG, "开始 RTSP 文件推流 file=${videoFile.name} url=$url")
        scope.launch {
            try {
                publishBlocking(context.applicationContext, videoFile, url)
            } finally {
                busy.set(false)
            }
        }
    }

    private suspend fun publishBlocking(context: Context, videoFile: File, url: String) {
        withContext(Dispatchers.Main) {
            val finished = AtomicBoolean(false)
            val streamer = RtspFromFile(
                object : ConnectChecker {
                    override fun onConnectionStarted(url: String) {
                        Log.i(TAG, "RTSP connecting $url")
                    }

                    override fun onConnectionSuccess() {
                        Log.i(TAG, "RTSP 连接成功")
                    }

                    override fun onConnectionFailed(reason: String) {
                        Log.w(TAG, "RTSP 连接失败: $reason")
                        finished.set(true)
                    }

                    override fun onNewBitrate(bitrate: Long) = Unit

                    override fun onDisconnect() {
                        Log.i(TAG, "RTSP 断开")
                        finished.set(true)
                    }

                    override fun onAuthError() {
                        Log.w(TAG, "RTSP 鉴权失败")
                        finished.set(true)
                    }

                    override fun onAuthSuccess() {
                        Log.i(TAG, "RTSP 鉴权成功")
                    }
                },
                object : VideoDecoderInterface {
                    override fun onVideoDecoderFinished() {
                        Log.i(TAG, "视频解码结束，停止推流")
                        finished.set(true)
                    }
                },
                object : AudioDecoderInterface {
                    override fun onAudioDecoderFinished() = Unit
                },
            )
            streamer.setLoopMode(false)
            val uri = Uri.fromFile(videoFile)
            val videoOk = runCatching { streamer.prepareVideo(context, uri) }.getOrDefault(false)
            val audioOk = runCatching { streamer.prepareAudio(context, uri) }.getOrDefault(false)
            if (!videoOk && !audioOk) {
                Log.w(TAG, "prepareVideo/Audio 失败，无法推流")
                return@withContext
            }
            runCatching { streamer.startStream(url) }
                .onFailure { Log.w(TAG, "startStream 失败", it) }
            // 等待播完或失败（最长 30 分钟）
            withContext(Dispatchers.IO) {
                val deadline = System.currentTimeMillis() + 30 * 60_000L
                while (!finished.get() && System.currentTimeMillis() < deadline) {
                    if (!streamer.isStreaming) break
                    Thread.sleep(500)
                }
            }
            runCatching {
                if (streamer.isStreaming) streamer.stopStream()
            }
            Log.i(TAG, "RTSP 文件推流结束 file=${videoFile.name}")
        }
    }
}
