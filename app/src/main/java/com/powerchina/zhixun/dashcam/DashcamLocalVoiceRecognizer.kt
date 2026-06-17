package com.powerchina.zhixun.dashcam

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class DashcamLocalVoiceRecognizer(
    context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false

    private val recognitionIntent: Intent by lazy {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.bestText().orEmpty()
            if (text.isNotBlank()) mainHandler.post { onPartial(text) }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results?.bestText().orEmpty()
            Log.i(DashcamAsrUploader.TAG, "本机识别结果: $text")
            mainHandler.post {
                if (text.isNotBlank()) onFinal(text) else onError("未识别到语音内容")
            }
        }

        override fun onError(error: Int) {
            listening = false
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音内容"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "说话时间太短"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要麦克风权限"
                else -> "本机语音识别失败"
            }
            Log.w(DashcamAsrUploader.TAG, "本机识别 onError=$error message=$message")
            mainHandler.post { onError(message) }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    fun startListening(): Boolean {
        if (listening) return true
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            Log.w(DashcamAsrUploader.TAG, "设备不支持 SpeechRecognizer")
            onError("设备不支持语音识别")
            return false
        }
        return runCatching {
            ensureRecognizer()
            listening = true
            Log.i(DashcamAsrUploader.TAG, "本机识别 startListening")
            speechRecognizer?.startListening(recognitionIntent)
            true
        }.getOrElse {
            listening = false
            Log.e(DashcamAsrUploader.TAG, "本机识别 startListening 失败", it)
            onError(it.message ?: "无法启动本机语音识别")
            false
        }
    }

    fun stopListening() {
        if (!listening) return
        runCatching { speechRecognizer?.stopListening() }
    }

    fun cancel() {
        listening = false
        runCatching { speechRecognizer?.cancel() }
    }

    fun destroy() {
        cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun ensureRecognizer() {
        if (speechRecognizer != null) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
            setRecognitionListener(listener)
        }
    }

    private fun Bundle.bestText(): String =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
}
