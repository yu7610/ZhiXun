package com.powerchina.zhixun.dashcam

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import com.powerchina.zhixun.network.OkHttpClientFactory
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

object DashcamAsrUploader {

    const val TAG = "DashcamAsr"
    const val ASR_URL = "http://8.134.202.195:18006/asr"

    @Volatile
    private var httpClient: OkHttpClient? = null

    @Volatile
    private var cloudAsrReachable: Boolean? = null

    private fun client(context: Context): OkHttpClient {
        return httpClient ?: synchronized(this) {
            httpClient ?: OkHttpClientFactory.create(
                context = context.applicationContext,
                connectTimeoutSec = 15,
                readTimeoutSec = 120,
                writeTimeoutSec = 120,
            ).also { httpClient = it }
        }
    }

    fun resetReachabilityCache() {
        cloudAsrReachable = null
    }

    fun transcribe(context: Context, audioFile: File): Result<String> {
        var uploadFile: File? = null
        val result = runCatching {
            require(audioFile.exists() && audioFile.length() > 0L) { "录音文件为空" }
            require(audioFile.extension.equals("m4a", ignoreCase = true)) {
                "ASR 仅接受 m4a，当前: ${audioFile.name}"
            }
            val prepared = prepareUploadFile(context, audioFile)
            uploadFile = prepared
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    prepared.name,
                    prepared.asRequestBody("audio/mp4".toMediaType()),
                )
                .addFormDataPart("language", "auto")
                .build()
            Log.i(TAG, "POST $ASR_URL path=${prepared.absolutePath} size=${prepared.length()}B")
            val request = Request.Builder().url(ASR_URL).post(body).build()
            client(context).newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                Log.i(TAG, "HTTP ${response.code} 响应: $raw")
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}: $raw")
                }
                parseTranscript(raw) ?: throw IllegalStateException("未识别到语音内容")
            }
        }.onFailure { e ->
            if (isConnectionError(e)) {
                cloudAsrReachable = false
                Log.w(TAG, "云端 ASR 不可达: ${e.message}")
            } else {
                Log.e(TAG, "ASR 失败", e)
            }
        }
        if (uploadFile != null && uploadFile != audioFile) {
            runCatching { uploadFile?.delete() }
        }
        if (result.isSuccess) {
            cloudAsrReachable = true
        }
        return result
    }

    private fun prepareUploadFile(context: Context, audioFile: File): File {
        val remuxed = File(context.cacheDir, "${audioFile.nameWithoutExtension}_asr.m4a")
        return DashcamM4aRemuxer.remux(audioFile, remuxed).getOrElse {
            Log.w(TAG, "remux 失败，使用原始 m4a 上传", it)
            audioFile
        }
    }

    fun isConnectionError(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            when (current) {
                is ConnectException,
                is UnknownHostException,
                is SocketTimeoutException,
                -> return true
            }
            current = current.cause
        }
        return false
    }

    fun friendlyMessage(error: Throwable): String {
        if (isConnectionError(error)) {
            return "语音识别服务暂不可用，请确认 18006 端口已启动"
        }
        return error.message?.takeIf { it.isNotBlank() } ?: "语音识别失败"
    }

    fun parseTranscript(raw: String): String? = runCatching {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return stripAsrTags(trimmed)
        }
        val root = JsonParser.parseString(trimmed)
        when {
            root.isJsonPrimitive && root.asJsonPrimitive.isString ->
                stripAsrTags(root.asString.trim())
            root.isJsonObject -> {
                val obj = root.asJsonObject
                for (key in listOf("text", "result", "transcript", "transcription")) {
                    val element = obj.get(key) ?: continue
                    if (element.isJsonNull) continue
                    if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                        val value = element.asString.trim()
                        if (value.isNotBlank()) return@runCatching stripAsrTags(value)
                    }
                }
                obj.getAsJsonArray("segments")?.firstOrNull()?.asJsonObject
                    ?.get("text")?.asString?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::stripAsrTags)
            }
            else -> null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** 去掉 ASR 返回中的 `<|zh|><|NEUTRAL|>...` 等标签，只保留可读中文。 */
    fun stripAsrTags(text: String): String =
        text.replace(Regex("<\\|[^|]+\\|>"), "").trim()
}
