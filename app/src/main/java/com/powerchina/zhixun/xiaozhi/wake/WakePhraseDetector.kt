package com.powerchina.zhixun.xiaozhi.wake

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlin.math.sqrt

/**
 * 语音唤醒：优先 AudioRecord VAD + SpeechRecognizer；失败时降级为 SpeechRecognizer 连续监听。
 */
class WakePhraseDetector(
    private val context: Context,
    private val onWakeDetected: () -> Unit,
) : WakeListener {
    companion object {
        private const val TAG = "ZhiXunVoiceWake"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_MS = 30
        private const val SPEECH_RMS_THRESHOLD = 350.0
        private const val SPEECH_HOLD_FRAMES = 3
        private const val MAX_RECOGNIZE_MS = 6_000L
        private const val RESTART_DELAY_MS = 300L
        private const val HEARTBEAT_MS = 10_000L
        private const val MAX_AUDIO_FAIL = 3
    }

    private enum class Mode { VAD, RECOGNIZER_ONLY }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext

    private var speechRecognizer: SpeechRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var vadThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var recognizeTimeoutRunnable: Runnable? = null
    private var heartbeatRunnable: Runnable? = null
    private var audioFailCount = 0
    private var mode = Mode.VAD

    @Volatile
    private var active = false
    override val isActive: Boolean get() = active

    @Volatile
    private var recognizing = false

    private val recognitionIntent: Intent by lazy {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "SpeechRecognizer 就绪 (mode=$mode)")
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            Log.w(TAG, "SpeechRecognizer 错误: ${errorName(error)} (mode=$mode)")
            if (mode == Mode.RECOGNIZER_ONLY) {
                scheduleRecognizerOnlyRestart(restartDelayFor(error))
            } else {
                finishRecognizeAndResumeVad()
            }
        }

        override fun onResults(results: Bundle?) {
            if (checkResults(results)) return
            if (mode == Mode.RECOGNIZER_ONLY) {
                scheduleRecognizerOnlyRestart(RESTART_DELAY_MS)
            } else {
                finishRecognizeAndResumeVad()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            checkResults(partialResults)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** 启动或重启监听（幂等） */
    override fun start() {
        val srAvailable = SpeechRecognizer.isRecognitionAvailable(appContext)
        Log.i(
            TAG,
            "启动唤醒引擎 SpeechRecognizer可用=$srAvailable 关键词=${WakePhraseMatcher.WAKE_PHRASE}",
        )
        if (!srAvailable) {
            Log.e(TAG, "设备无语音识别引擎，无法语音唤醒")
            return
        }
        stopInternal()
        active = true
        audioFailCount = 0
        mode = Mode.VAD
        acquireWakeLock()
        mainHandler.post {
            ensureSpeechRecognizer()
            startVadLoop()
            scheduleHeartbeat()
        }
    }

    override fun pause() {
        if (!active) return
        Log.d(TAG, "暂停唤醒监听")
        active = false
        recognizing = false
        mainHandler.post { stopInternalKeepRecognizer() }
    }

    override fun stop() {
        Log.d(TAG, "停止唤醒监听")
        active = false
        recognizing = false
        mainHandler.post {
            cancelHeartbeat()
            stopInternal()
            releaseWakeLock()
        }
    }

    private fun stopInternal() {
        cancelHeartbeat()
        stopVadLoop()
        stopSpeechRecognizerSession()
        destroySpeechRecognizer()
    }

    private fun stopInternalKeepRecognizer() {
        cancelHeartbeat()
        stopVadLoop()
        stopSpeechRecognizerSession()
    }

    private fun ensureSpeechRecognizer() {
        if (speechRecognizer != null) return
        val component = findRecognitionService()
        speechRecognizer = if (component != null) {
            Log.i(TAG, "绑定语音识别: ${component.packageName}/${component.shortClassName}")
            SpeechRecognizer.createSpeechRecognizer(appContext, component)
        } else {
            Log.w(TAG, "使用默认 SpeechRecognizer")
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }
        speechRecognizer?.setRecognitionListener(listener)
    }

    private fun destroySpeechRecognizer() {
        stopSpeechRecognizerSession()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun switchToRecognizerOnly() {
        if (mode == Mode.RECOGNIZER_ONLY) return
        mode = Mode.RECOGNIZER_ONLY
        Log.w(TAG, "AudioRecord 不可用，降级为 SpeechRecognizer 连续监听模式")
        stopVadLoop()
        beginRecognizerOnlySession()
    }

    private fun startVadLoop() {
        if (!active || recognizing || mode != Mode.VAD) return
        stopVadLoop()
        if (!createAudioRecord()) {
            audioFailCount++
            Log.w(TAG, "AudioRecord 创建失败 ($audioFailCount/$MAX_AUDIO_FAIL)")
            if (audioFailCount >= MAX_AUDIO_FAIL) {
                switchToRecognizerOnly()
                return
            }
            mainHandler.postDelayed({ startVadLoop() }, 1_000L)
            return
        }
        audioFailCount = 0
        vadThread = Thread({
            val frameSize = SAMPLE_RATE * FRAME_MS / 1000
            val buffer = ShortArray(frameSize)
            var speechFrames = 0
            var lastHeartbeat = System.currentTimeMillis()
            try {
                audioRecord?.startRecording()
                Log.i(TAG, "AudioRecord VAD 已开始")
                while (active && !recognizing && mode == Mode.VAD) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                    if (read <= 0) continue
                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeat >= HEARTBEAT_MS) {
                        val rms = computeRms(buffer, read)
                        Log.d(TAG, "VAD 心跳 rms=${rms.toInt()} mode=$mode active=$active")
                        lastHeartbeat = now
                    }
                    val rms = computeRms(buffer, read)
                    if (rms >= SPEECH_RMS_THRESHOLD) {
                        speechFrames++
                        if (speechFrames >= SPEECH_HOLD_FRAMES) {
                            Log.i(TAG, "检测到说话 rms=${rms.toInt()}，开始识别")
                            mainHandler.post { beginRecognizeFromVad() }
                            break
                        }
                    } else {
                        speechFrames = 0
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "VAD 线程异常", e)
            } finally {
                releaseAudioRecord()
            }
        }, "WakeVad").also { it.start() }
    }

    private fun stopVadLoop() {
        vadThread?.interrupt()
        vadThread = null
        releaseAudioRecord()
    }

    private fun beginRecognizeFromVad() {
        if (!active || recognizing) return
        recognizing = true
        stopVadLoop()
        try {
            ensureSpeechRecognizer()
            speechRecognizer?.startListening(recognitionIntent)
            recognizeTimeoutRunnable = Runnable { finishRecognizeAndResumeVad() }
            mainHandler.postDelayed(recognizeTimeoutRunnable!!, MAX_RECOGNIZE_MS)
        } catch (e: Exception) {
            Log.e(TAG, "startListening 失败", e)
            finishRecognizeAndResumeVad()
        }
    }

    private fun beginRecognizerOnlySession() {
        if (!active || recognizing) return
        recognizing = true
        try {
            ensureSpeechRecognizer()
            speechRecognizer?.startListening(recognitionIntent)
            recognizeTimeoutRunnable = Runnable {
                recognizing = false
                scheduleRecognizerOnlyRestart(RESTART_DELAY_MS)
            }
            mainHandler.postDelayed(recognizeTimeoutRunnable!!, MAX_RECOGNIZE_MS)
        } catch (e: Exception) {
            Log.e(TAG, "连续识别 startListening 失败", e)
            recognizing = false
            scheduleRecognizerOnlyRestart(1_000L)
        }
    }

    private fun scheduleRecognizerOnlyRestart(delayMs: Long) {
        if (!active || mode != Mode.RECOGNIZER_ONLY) return
        recognizing = false
        stopSpeechRecognizerSession()
        mainHandler.postDelayed({ beginRecognizerOnlySession() }, delayMs)
    }

    private fun finishRecognizeAndResumeVad() {
        if (!recognizing && mode == Mode.VAD) return
        recognizing = false
        stopSpeechRecognizerSession()
        if (active && mode == Mode.VAD) {
            mainHandler.postDelayed({ startVadLoop() }, RESTART_DELAY_MS)
        }
    }

    private fun stopSpeechRecognizerSession() {
        recognizeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        recognizeTimeoutRunnable = null
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "停止 SpeechRecognizer 异常", e)
        }
    }

    /** @return true if wake word matched */
    private fun checkResults(results: Bundle?): Boolean {
        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        for (text in texts) {
            Log.d(TAG, "识别结果: $text")
            if (WakePhraseMatcher.matches(text)) {
                Log.i(TAG, "★ 检测到唤醒词「${WakePhraseMatcher.WAKE_PHRASE}」: $text")
                active = false
                recognizing = false
                mainHandler.post {
                    stopInternal()
                    releaseWakeLock()
                    onWakeDetected()
                }
                return true
            }
        }
        return false
    }

    private fun createAudioRecord(): Boolean {
        if (audioRecord != null) return true
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            Log.e(TAG, "无效 AudioRecord buffer=$minBuffer")
            return false
        }
        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        )
        for (source in sources) {
            try {
                val record = AudioRecord(source, SAMPLE_RATE, CHANNEL, ENCODING, minBuffer * 2)
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

    private fun releaseAudioRecord() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }

    private fun scheduleHeartbeat() {
        cancelHeartbeat()
        heartbeatRunnable = Runnable {
            if (!active) return@Runnable
            Log.d(TAG, "唤醒引擎心跳 mode=$mode active=$active recognizing=$recognizing")
            scheduleHeartbeat()
        }
        mainHandler.postDelayed(heartbeatRunnable!!, HEARTBEAT_MS)
    }

    private fun cancelHeartbeat() {
        heartbeatRunnable?.let { mainHandler.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    private fun computeRms(buffer: ShortArray, size: Int): Double {
        var sum = 0.0
        for (i in 0 until size) {
            val v = buffer[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / size)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZhiXun:WakeListen").apply {
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

    private fun findRecognitionService(): ComponentName? {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        @Suppress("DEPRECATION")
        val services = appContext.packageManager.queryIntentServices(
            intent,
            PackageManager.GET_META_DATA,
        )
        if (services.isEmpty()) {
            Log.w(TAG, "未找到 RecognitionService 组件")
            return null
        }
        services.forEach {
            Log.d(TAG, "可用 RecognitionService: ${it.serviceInfo.packageName}/${it.serviceInfo.name}")
        }
        val info = services.first().serviceInfo
        return ComponentName(info.packageName, info.name)
    }

    private fun restartDelayFor(error: Int): Long {
        return when (error) {
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 800L
            SpeechRecognizer.ERROR_NO_MATCH -> 200L
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 200L
            SpeechRecognizer.ERROR_CLIENT -> 600L
            else -> RESTART_DELAY_MS
        }
    }

    private fun errorName(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            else -> "ERROR_$error"
        }
    }
}
