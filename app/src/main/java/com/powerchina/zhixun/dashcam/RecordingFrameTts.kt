package com.powerchina.zhixun.dashcam

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 录屏帧隐患检测结果：系统自带 TTS 播报（独立于小智语音链路）。
 */
object RecordingFrameTts {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val initialized = AtomicBoolean(false)
    private val initializing = AtomicBoolean(false)

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var pendingText: String? = null

    @Volatile
    private var pendingOnDone: (() -> Unit)? = null

    @Volatile
    private var lastSpokenText: String? = null

    @Volatile
    private var speakDoneCallback: (() -> Unit)? = null

    private var installedEngines: List<String> = emptyList()
    private var engineAttemptIndex = 0

    fun warmUp(context: Context) {
        mainHandler.post {
            ensureTts(context.applicationContext)
        }
    }

    fun speak(context: Context, text: String, onDone: (() -> Unit)? = null) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            onDone?.let { mainHandler.post(it) }
            return
        }
        mainHandler.post {
            speakOnMain(context.applicationContext, trimmed, onDone)
        }
    }

    private fun speakOnMain(app: Context, text: String, onDone: (() -> Unit)?) {
        if (text == lastSpokenText) {
            Log.d(RecordingFrameUploader.TAG, "TTS 跳过重复: $text")
            onDone?.invoke()
            return
        }
        if (initialized.get()) {
            doSpeak(text, onDone)
            return
        }
        pendingText = text
        pendingOnDone = onDone
        ensureTts(app)
    }

    private fun ensureTts(app: Context) {
        if (initialized.get() || initializing.get()) return
        if (installedEngines.isEmpty()) {
            installedEngines = listInstalledEngines(app)
            engineAttemptIndex = 0
        }
        val engine = installedEngines.getOrNull(engineAttemptIndex)
        if (engine == null) {
            Log.e(RecordingFrameUploader.TAG, "系统 TTS 不可用：未找到可用引擎")
            pendingText = null
            val cb = pendingOnDone
            pendingOnDone = null
            cb?.invoke()
            return
        }
        initializing.set(true)
        Log.i(
            RecordingFrameUploader.TAG,
            "系统 TTS 初始化 engine=$engine (${engineAttemptIndex + 1}/${installedEngines.size})",
        )
        tts = TextToSpeech(
            app,
            { status -> onTtsInit(app, status) },
            engine,
        )
    }

    private fun onTtsInit(app: Context, status: Int) {
        initializing.set(false)
        if (status != TextToSpeech.SUCCESS) {
            Log.e(
                RecordingFrameUploader.TAG,
                "系统 TTS 初始化失败 status=$status engine=${installedEngines.getOrNull(engineAttemptIndex)}",
            )
            tts?.shutdown()
            tts = null
            engineAttemptIndex++
            if (engineAttemptIndex < installedEngines.size) {
                ensureTts(app)
            } else {
                Log.e(RecordingFrameUploader.TAG, "所有 TTS 引擎初始化失败")
                pendingText = null
                val cb = pendingOnDone
                pendingOnDone = null
                cb?.invoke()
            }
            return
        }
        finishInit()
    }

    private fun finishInit() {
        val engine = tts ?: return
        when (engine.setLanguage(Locale.SIMPLIFIED_CHINESE)) {
            TextToSpeech.LANG_MISSING_DATA,
            TextToSpeech.LANG_NOT_SUPPORTED,
            -> {
                Log.w(RecordingFrameUploader.TAG, "简体中文不可用，使用默认语言")
                engine.language = Locale.getDefault()
            }
        }
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    mainHandler.post { invokeSpeakDone() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    mainHandler.post { invokeSpeakDone() }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    mainHandler.post { invokeSpeakDone() }
                }
            },
        )
        initialized.set(true)
        Log.i(RecordingFrameUploader.TAG, "系统 TTS 就绪 language=${engine.language}")
        pendingText?.let { queued ->
            pendingText = null
            val cb = pendingOnDone
            pendingOnDone = null
            doSpeak(queued, cb)
        }
    }

    private fun invokeSpeakDone() {
        val cb = speakDoneCallback
        speakDoneCallback = null
        cb?.invoke()
    }

    private fun doSpeak(text: String, onDone: (() -> Unit)?) {
        val engine = tts ?: run {
            onDone?.invoke()
            return
        }
        speakDoneCallback = onDone
        lastSpokenText = text
        val utteranceId = "rec_frame_${System.currentTimeMillis()}"
        Log.i(RecordingFrameUploader.TAG, "TTS 播报: $text")
        val started = engine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId,
        )
        if (started != TextToSpeech.SUCCESS) {
            Log.e(RecordingFrameUploader.TAG, "TTS speak 失败 code=$started")
            invokeSpeakDone()
        }
    }

    private fun listInstalledEngines(context: Context): List<String> {
        val pm = context.packageManager
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        @Suppress("DEPRECATION")
        val services = pm.queryIntentServices(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val installed = services.mapNotNull { it.serviceInfo?.packageName }.distinct()
        if (installed.isEmpty()) {
            Log.e(RecordingFrameUploader.TAG, "未安装 TTS 引擎")
            return emptyList()
        }
        Log.i(RecordingFrameUploader.TAG, "已安装 TTS 引擎: $installed")
        return installed
    }

    fun resetDedupe() {
        lastSpokenText = null
        pendingText = null
        pendingOnDone = null
    }

    fun shutdown() {
        mainHandler.post {
            resetDedupe()
            speakDoneCallback = null
            initialized.set(false)
            initializing.set(false)
            engineAttemptIndex = 0
            installedEngines = emptyList()
            tts?.stop()
            tts?.shutdown()
            tts = null
        }
    }
}
