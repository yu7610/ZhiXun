package com.powerchina.zhixun.xiaozhi

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.powerchina.zhixun.network.OkHttpClientFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class VisionExplainResult(
    val success: Boolean,
    val response: String,
    val rawJson: String,
)

/**
 * 拍照隐患检测 HTTP 接口（multipart/form-data）。
 */
object XiaozhiVisionClient {

    private const val TAG = "XiaozhiVision"
    const val DETECT_IMAGE_URL = "http://8.134.202.195:6086/recoder/detectImageFile"
    private const val NO_HAZARD_TEXT = "无安全隐患"

    @Volatile
    private var httpClient: OkHttpClient? = null

    private fun client(context: Context): OkHttpClient {
        return httpClient ?: synchronized(this) {
            httpClient ?: OkHttpClientFactory.create(
                context = context.applicationContext,
                connectTimeoutSec = 15,
                readTimeoutSec = 60,
                writeTimeoutSec = 60,
            ).also { httpClient = it }
        }
    }

    fun detectImageFile(
        context: Context,
        deviceId: String,
        jpegBytes: ByteArray,
        filename: String,
    ): Result<VisionExplainResult> = runCatching {
        val mac = normalizeMacWithColons(deviceId)
        require(mac.isNotBlank()) { "未配置设备 MAC 地址" }
        val safeName = filename.ifBlank { "photo.jpg" }
        val topic = "drone/device/$mac"

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("deviceId", mac)
            .addFormDataPart("topic", topic)
            .addFormDataPart(
                "image",
                safeName,
                jpegBytes.toRequestBody("image/jpeg".toMediaType()),
            )
            .build()

        Log.i(
            TAG,
            "POST detectImage deviceId=$mac topic=$topic file=$safeName jpeg=${jpegBytes.size}B",
        )

        val request = Request.Builder()
            .url(DETECT_IMAGE_URL)
            .post(body)
            .build()

        client(context).newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (response.code != 200) {
                throw IllegalStateException("隐患检测 HTTP ${response.code}: $raw")
            }
            parseDetectImageResponse(raw, safeName)
        }
    }.onFailure { e ->
        Log.e(TAG, "隐患检测失败", e)
    }

    /** MAC 地址保留 ":" 分隔 */
    fun normalizeMacWithColons(deviceId: String): String {
        val trimmed = deviceId.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.contains(":")) {
            return trimmed.uppercase()
        }
        val hex = trimmed.replace("-", "").replace(":", "")
        if (hex.length == 12 && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            return hex.chunked(2).joinToString(":").uppercase()
        }
        return trimmed
    }

    private fun parseDetectImageResponse(raw: String, filename: String): VisionExplainResult {
        val root = JsonParser.parseString(raw).asJsonObject
        val data = root.getAsJsonObject("data")
        val text = data?.get("text")?.asString?.trim().orEmpty()
        val responseText = if (text.isNotBlank()) {
            JsonObject().apply {
                addProperty("success", true)
                addProperty("filename", filename)
                addProperty("text", text)
            }.toString()
        } else {
            NO_HAZARD_TEXT
        }
        Log.i(
            TAG,
            "隐患检测完成 hasText=${text.isNotBlank()} len=${responseText.length}",
        )
        return VisionExplainResult(
            success = true,
            response = responseText,
            rawJson = raw,
        )
    }

    /** 展示/语音播报用：从 detect 结果中提取可读文本 */
    fun displayTextFromResult(result: VisionExplainResult): String {
        val trimmed = result.response.trim()
        if (trimmed == NO_HAZARD_TEXT) return NO_HAZARD_TEXT
        return runCatching {
            val json = JsonParser.parseString(trimmed).asJsonObject
            json.get("text")?.asString?.trim()?.ifBlank { null } ?: trimmed
        }.getOrDefault(trimmed)
    }

    fun buildToolCallResult(description: String): JsonObject {
        val text = displayTextFromResult(
            VisionExplainResult(success = true, response = description, rawJson = ""),
        )
        val content = com.google.gson.JsonArray()
        content.add(
            JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", text)
            },
        )
        return JsonObject().apply {
            add("content", content)
            addProperty("isError", false)
        }
    }
}
