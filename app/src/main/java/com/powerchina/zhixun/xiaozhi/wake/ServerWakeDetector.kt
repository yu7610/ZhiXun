package com.powerchina.zhixun.xiaozhi.wake

import android.app.Application
import android.util.Log
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.powerchina.zhixun.audio.utils.OpusEncoder
import com.powerchina.zhixun.data.ConfigManager
import com.powerchina.zhixun.network.WebSocketEvent
import com.powerchina.zhixun.xiaozhi.XiaozhiSessionManager
import com.powerchina.zhixun.xiaozhi.VoiceFlowLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 无系统 SpeechRecognizer 时：持续向小智服务端送 Opus 音频，由 STT 识别「你好」。
 */
class ServerWakeDetector(
    private val context: Context,
    private val onWakeDetected: () -> Unit,
) : WakeListener {

    companion object {
        private const val TAG = "WakeSTT"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_MS = 60
        private const val FRAME_BYTES = SAMPLE_RATE * FRAME_MS / 1000 * 2
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        /** 服务端 listen 约 30s 超时，定期续期 listen/start */
        /** 服务端 listen 会话约 30s 超时，stop+start 续期需早于该阈值 */
        private const val LISTEN_REFRESH_INTERVAL_MS = 12_000L
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionManager =
        XiaozhiSessionManager.getInstance(appContext as Application)
    private val webSocket = sessionManager.webSocketManager

    @Volatile
    private var active = false
    @Volatile
    private var streaming = false
    @Volatile
    private var startingUp = false
    override val isActive: Boolean get() = active
    override val isStreaming: Boolean get() = streaming
    override val isStarting: Boolean get() = active && startingUp && !streaming

    private var streamingJob: Job? = null
    private var eventJob: Job? = null
    private var removeTextListener: (() -> Unit)? = null
    private var audioRecord: AudioRecord? = null
    private var opusEncoder: OpusEncoder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var streamGeneration = 0

    override fun start() {
        if (active) {
            Log.d(TAG, "已在运行，跳过 start")
            return
        }
        if (!isConfigReady()) {
            Log.e(TAG, "小智未配置，无法使用服务端唤醒")
            return
        }
        active = true
        startingUp = true
        streaming = false
        streamGeneration++
        acquireWakeLock()
        registerTextListener()
        Log.i(TAG, "启动服务端 STT 唤醒，关键词=${WakePhraseMatcher.WAKE_PHRASE}")
        VoiceFlowLog.snapshot("wakeSTT.start", "gen=$streamGeneration keyword=${WakePhraseMatcher.WAKE_PHRASE}")
        sessionManager.ensureConnected()
        ensureEventJob()
        launchStreaming("start")
    }

    /** 对话结束后快速恢复：复用 eventJob，跳过完整 start 流程 */
    override fun resume() {
        if (active) {
            Log.d(TAG, "已在运行，跳过 resume")
            return
        }
        if (!isConfigReady()) {
            Log.e(TAG, "小智未配置，无法恢复服务端唤醒")
            return
        }
        active = true
        startingUp = true
        streaming = false
        streamGeneration++
        acquireWakeLock()
        registerTextListener()
        ensureEventJob()
        Log.i(TAG, "快速恢复服务端 STT 唤醒")
        VoiceFlowLog.snapshot("wakeSTT.resume", "gen=$streamGeneration hasRecord=${audioRecord?.state == AudioRecord.STATE_INITIALIZED}")
        launchStreaming("resume")
    }

    /** 结束语播放期间预创建 AudioRecord，缩短恢复唤醒时间 */
    fun prepareAudioCapture() {
        if (active || streaming) return
        if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) return
        if (createAudioRecord()) {
            Log.d(TAG, "预创建 AudioRecord 完成")
            VoiceFlowLog.step("wakeSTT.preAudio", "AudioRecord 预创建成功")
        }
    }

    fun canQuickResume(): Boolean =
        !active && audioRecord?.state == AudioRecord.STATE_INITIALIZED

    override fun pause() {
        if (!active) return
        Log.d(TAG, "暂停服务端唤醒")
        VoiceFlowLog.step("wakeSTT.pause", "gen=$streamGeneration frames=paused")
        active = false
        streaming = false
        startingUp = false
        streamingJob?.cancel()
        streamingJob = null
        unregisterTextListener()
        stopAudio()
        webSocket.sendStopListening()
    }

    override fun stop() {
        Log.d(TAG, "停止服务端唤醒")
        VoiceFlowLog.step("wakeSTT.stop", "gen=$streamGeneration")
        active = false
        streaming = false
        startingUp = false
        streamGeneration++
        unregisterTextListener()
        streamingJob?.cancel()
        streamingJob = null
        eventJob?.cancel()
        eventJob = null
        stopAudio()
        try {
            webSocket.sendStopListening()
        } catch (_: Exception) {
        }
        releaseWakeLock()
    }

    private fun markStartupFailed() {
        active = false
        streaming = false
        startingUp = false
    }

    private fun registerTextListener() {
        unregisterTextListener()
        removeTextListener = webSocket.addTextMessageListener { handleTextMessage(it) }
    }

    private fun unregisterTextListener() {
        removeTextListener?.invoke()
        removeTextListener = null
    }

    private fun ensureEventJob() {
        if (eventJob?.isActive == true) return
        eventJob = scope.launch { listenWebSocketEvents() }
    }

    private fun launchStreaming(reason: String) {
        val gen = streamGeneration
        streamingJob?.cancel()
        streamingJob = scope.launch {
            Log.d(TAG, "推流任务启动 reason=$reason gen=$gen")
            waitConnectAndStream(gen, reason)
        }
    }

    private suspend fun waitConnectAndStream(generation: Int, reason: String) {
        if (generation != streamGeneration || !active) return

        val alreadyConnected = webSocket.isConnected()
        val connected = if (alreadyConnected) {
            true
        } else {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                while (active && generation == streamGeneration && !webSocket.isConnected()) {
                    delay(200)
                }
                webSocket.isConnected()
            } ?: false
        }

        if (!active || generation != streamGeneration) return
        if (!connected) {
            Log.e(TAG, "WebSocket 未连接，服务端唤醒失败 gen=$generation，稍后重试")
            if (active && generation == streamGeneration) {
                delay(2_000)
                if (active && generation == streamGeneration) {
                    launchStreaming("connect_retry")
                }
            }
            return
        }

        Log.i(
            TAG,
            "WebSocket 就绪 session=${webSocket.getSessionId()} reason=$reason，发送 listen/start",
        )
        VoiceFlowLog.step(
            "wakeSTT.listen",
            "reason=$reason session=${webSocket.getSessionId()} gen=$generation",
        )
        webSocket.sendStopListening()
        if (reason == "resume" || reason == "start") {
            delay(80)
        }
        webSocket.sendStartListening("auto")
        streamAudioLoop(generation)
    }

    private suspend fun listenWebSocketEvents() {
        webSocket.events.collect { event ->
            if (!active) return@collect
            when (event) {
                is WebSocketEvent.Connected -> {
                    Log.i(TAG, "WebSocket Connected，重启唤醒推流")
                    VoiceFlowLog.snapshot("wakeSTT.wsConnected", "session=${webSocket.getSessionId()} gen=$streamGeneration")
                    launchStreaming("reconnect")
                }
                is WebSocketEvent.Disconnected -> {
                    Log.w(TAG, "WebSocket Disconnected，等待重连")
                    VoiceFlowLog.warn("wakeSTT.wsDisconnected", "session=${webSocket.getSessionId()} gen=$streamGeneration streaming=$streaming")
                }
                else -> Unit
            }
        }
    }

    private fun handleTextMessage(message: String) {
        if (!active) return
        try {
            val json = gson.fromJson(message, JsonObject::class.java)
            when (json.get("type")?.asString) {
                "stt" -> {
                    val text = json.get("text")?.asString ?: return
                    val matched = WakePhraseMatcher.matches(text)
                    Log.i(TAG, "STT: \"$text\" matched=$matched")
                    if (matched) {
                        triggerWake()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析 WebSocket 文本失败", e)
        }
    }

    private fun triggerWake() {
        if (!active) return
        active = false
        streaming = false
        startingUp = false
        streamGeneration++
        Log.i(TAG, "★ 命中唤醒词「${WakePhraseMatcher.WAKE_PHRASE}」")
        VoiceFlowLog.snapshot("wakeSTT.hit", "phrase=${WakePhraseMatcher.WAKE_PHRASE} gen=$streamGeneration")
        unregisterTextListener()
        streamingJob?.cancel()
        streamingJob = null
        eventJob?.cancel()
        eventJob = null
        // 取消服务端对已识别唤醒词的 TTS 回复，避免与对话页冲突
        webSocket.sendAbort("wake_handoff")
        webSocket.sendStopListening()
        stopAudio()
        releaseWakeLock()
        mainHandler.post { onWakeDetected() }
    }

    private suspend fun streamAudioLoop(generation: Int) {
        streaming = false
        val hasRecord = audioRecord?.state == AudioRecord.STATE_INITIALIZED
        if (!hasRecord) {
            stopAudio()
            if (!createAudioRecord()) {
                Log.e(TAG, "AudioRecord 创建失败")
                markStartupFailed()
                return
            }
        }
        try {
            opusEncoder = OpusEncoder(SAMPLE_RATE, 1, FRAME_MS)
        } catch (e: Exception) {
            Log.e(TAG, "OpusEncoder 初始化失败", e)
            markStartupFailed()
            return
        }

        val readBuffer = ByteArray(FRAME_BYTES)
        val pcmFrame = ByteArray(FRAME_BYTES)
        var pcmFilled = 0
        var framesSent = 0
        var encodeFailures = 0
        var notReadySkips = 0
        var lastHeartbeat = System.currentTimeMillis()
        var lastListenRefresh = System.currentTimeMillis()

        audioRecord?.startRecording()
        streaming = true
        startingUp = false
        Log.i(TAG, "Opus 推流开始 gen=$generation")
        VoiceFlowLog.snapshot("wakeSTT.streaming", "gen=$generation session=${webSocket.getSessionId()}")

        while (active && generation == streamGeneration && scope.isActive) {
            val read = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: break
            if (read <= 0) continue

            var offset = 0
            while (offset < read) {
                val toCopy = minOf(FRAME_BYTES - pcmFilled, read - offset)
                System.arraycopy(readBuffer, offset, pcmFrame, pcmFilled, toCopy)
                pcmFilled += toCopy
                offset += toCopy

                if (pcmFilled < FRAME_BYTES) continue
                pcmFilled = 0

                if (!webSocket.isConnected()) {
                    notReadySkips++
                    delay(200)
                    continue
                }

                val opus = opusEncoder?.encode(pcmFrame.copyOf())
                if (opus == null) {
                    encodeFailures++
                    continue
                }
                webSocket.sendBinaryMessage(opus)
                framesSent++

                val now = System.currentTimeMillis()
                if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                    Log.d(
                        TAG,
                        "心跳 gen=$generation frames=$framesSent encodeFail=$encodeFailures " +
                            "wsSkip=$notReadySkips session=${webSocket.getSessionId()}",
                    )
                    lastHeartbeat = now
                    if (now - lastListenRefresh >= LISTEN_REFRESH_INTERVAL_MS) {
                        Log.d(TAG, "续期 listen 会话 gen=$generation (stop+start)")
                        VoiceFlowLog.step(
                            "wakeSTT.listenRenew",
                            "gen=$generation session=${webSocket.getSessionId()} frames=$framesSent",
                        )
                        webSocket.sendStopListening()
                        webSocket.sendStartListening("auto")
                        lastListenRefresh = System.currentTimeMillis()
                    }
                }
            }
        }
        streaming = false
        startingUp = false
        Log.d(
            TAG,
            "推流结束 gen=$generation frames=$framesSent reason=${if (!active) "paused" else "cancelled"}",
        )
        VoiceFlowLog.step(
            "wakeSTT.streamingEnd",
            "gen=$generation frames=$framesSent reason=${if (!active) "paused" else "cancelled"}",
        )
    }

    private fun createAudioRecord(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (source in sources) {
            try {
                val record = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer * 2,
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = record
                    Log.i(TAG, "AudioRecord 就绪 source=$source buffer=$minBuffer")
                    return true
                }
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecord source=$source 失败: ${e.message}")
            }
        }
        return false
    }

    private fun stopAudio() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        opusEncoder?.release()
        opusEncoder = null
    }

    private fun isConfigReady(): Boolean {
        val cfg = ConfigManager(appContext).loadConfig()
        val hasEndpoint = cfg.otaUrl.isNotBlank() || cfg.websocketUrl.isNotBlank()
        return hasEndpoint && cfg.macAddress.isNotBlank() && cfg.token.isNotBlank()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZhiXun:ServerWake").apply {
            acquire(10 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }
}

interface WakeListener {
    val isActive: Boolean
    /** 是否正在采集/推流 */
    val isStreaming: Boolean get() = isActive
    /** 已 start 但尚未推流（连接 WS / 初始化麦克风） */
    val isStarting: Boolean get() = false
    fun start()
    fun resume() = start()
    fun pause()
    fun stop()
}
