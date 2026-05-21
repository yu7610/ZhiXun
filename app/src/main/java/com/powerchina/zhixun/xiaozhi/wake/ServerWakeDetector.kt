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
    override val isActive: Boolean get() = active

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
        streamGeneration++
        acquireWakeLock()
        registerTextListener()
        Log.i(TAG, "启动服务端 STT 唤醒，关键词=${WakePhraseMatcher.WAKE_PHRASE}")
        sessionManager.ensureConnected()
        eventJob = scope.launch { listenWebSocketEvents() }
        launchStreaming("start")
    }

    override fun pause() {
        if (!active) return
        Log.d(TAG, "暂停服务端唤醒")
        active = false
        streamingJob?.cancel()
        streamingJob = null
        unregisterTextListener()
        stopAudio()
        webSocket.sendStopListening()
    }

    override fun stop() {
        Log.d(TAG, "停止服务端唤醒")
        active = false
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

    private fun registerTextListener() {
        unregisterTextListener()
        removeTextListener = webSocket.addTextMessageListener { handleTextMessage(it) }
    }

    private fun unregisterTextListener() {
        removeTextListener?.invoke()
        removeTextListener = null
    }

    private fun launchStreaming(reason: String) {
        val gen = streamGeneration
        streamingJob?.cancel()
        streamingJob = scope.launch {
            Log.d(TAG, "推流任务启动 reason=$reason gen=$gen")
            waitConnectAndStream(gen)
        }
    }

    private suspend fun waitConnectAndStream(generation: Int) {
        if (generation != streamGeneration || !active) return

        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            while (active && generation == streamGeneration && !webSocket.isConnected()) {
                delay(200)
            }
            webSocket.isConnected()
        } ?: false

        if (!active || generation != streamGeneration) return
        if (!connected) {
            Log.e(TAG, "WebSocket 未连接，服务端唤醒失败 gen=$generation")
            return
        }

        Log.i(
            TAG,
            "WebSocket 就绪 session=${webSocket.getSessionId()}，发送 listen/start",
        )
        webSocket.sendStartListening("auto")
        streamAudioLoop(generation)
    }

    private suspend fun listenWebSocketEvents() {
        webSocket.events.collect { event ->
            if (!active) return@collect
            when (event) {
                is WebSocketEvent.Connected -> {
                    Log.i(TAG, "WebSocket Connected，重启唤醒推流")
                    launchStreaming("reconnect")
                }
                is WebSocketEvent.Disconnected -> {
                    Log.w(TAG, "WebSocket Disconnected，等待重连")
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
        streamGeneration++
        Log.i(TAG, "★ 命中唤醒词「${WakePhraseMatcher.WAKE_PHRASE}」")
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
        stopAudio()
        if (!createAudioRecord()) {
            Log.e(TAG, "AudioRecord 创建失败")
            return
        }
        try {
            opusEncoder = OpusEncoder(SAMPLE_RATE, 1, FRAME_MS)
        } catch (e: Exception) {
            Log.e(TAG, "OpusEncoder 初始化失败", e)
            return
        }

        val readBuffer = ByteArray(FRAME_BYTES)
        val pcmFrame = ByteArray(FRAME_BYTES)
        var pcmFilled = 0
        var framesSent = 0
        var encodeFailures = 0
        var notReadySkips = 0
        var lastHeartbeat = System.currentTimeMillis()

        audioRecord?.startRecording()
        Log.i(TAG, "Opus 推流开始 gen=$generation")

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
                }
            }
        }
        Log.d(
            TAG,
            "推流结束 gen=$generation frames=$framesSent reason=${if (!active) "paused" else "cancelled"}",
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
    fun start()
    fun pause()
    fun stop()
}
