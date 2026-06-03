package com.powerchina.zhixun.dashcam

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
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
    private var lastSpokenText: String? = null

    private var installedEngines: List<String> = emptyList()
    private var engineAttemptIndex = 0

    fun warmUp(context: Context) {
        mainHandler.post {
            ensureTts(context.applicationContext)
        }
    }

    fun speak(context: Context, text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        mainHandler.post {
            speakOnMain(context.applicationContext, trimmed)
        }
    }

    private fun speakOnMain(app: Context, text: String) {
        if (text == lastSpokenText) {
            Log.d(RecordingFrameUploader.TAG, "TTS 跳过重复: $text")
            return
        }
        if (initialized.get()) {
            doSpeak(text)
            return
        }
        pendingText = text
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
        initialized.set(true)
        Log.i(RecordingFrameUploader.TAG, "系统 TTS 就绪 language=${engine.language}")
        pendingText?.let { queued ->
            pendingText = null
            doSpeak(queued)
        }
    }

    private fun doSpeak(text: String) {
        val engine = tts ?: return
        lastSpokenText = text
        Log.i(RecordingFrameUploader.TAG, "TTS 播报: $text")
        engine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "rec_frame_${System.currentTimeMillis()}",
        )
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
    }

    fun shutdown() {
        mainHandler.post {
            resetDedupe()
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
