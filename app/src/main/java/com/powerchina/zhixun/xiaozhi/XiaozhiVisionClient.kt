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
 * 通过 MCP 下发的 HTTP 端点上传图片（与 ESP32 camera->Explain 一致）。
 */
object XiaozhiVisionClient {

    private const val TAG = "XiaozhiVision"

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

    fun explain(
        context: Context,
        config: XiaozhiVisionConfig,
        jpegBytes: ByteArray,
        question: String,
    ): Result<VisionExplainResult> = runCatching {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("question", question)
            .addFormDataPart(
                "file",
                "camera.jpg",
                jpegBytes.toRequestBody("image/jpeg".toMediaType()),
            )
            .build()

        val requestBuilder = Request.Builder()
            .url(config.url)
            .post(body)
            .header("Device-Id", config.deviceId)
            .header("Client-Id", config.clientId)

        if (config.token.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${config.token}")
        }

        Log.i(
            TAG,
            "POST vision url=${config.url} jpeg=${jpegBytes.size}B question=$question",
        )

        client(context).newCall(requestBuilder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("视觉接口 HTTP ${response.code}: $raw")
            }
            parseVisionResponse(raw)
        }
    }.onFailure { e ->
        Log.e(TAG, "视觉分析失败", e)
    }

    private fun parseVisionResponse(raw: String): VisionExplainResult {
        val root = JsonParser.parseString(raw).asJsonObject
        val success = root.get("success")?.asBoolean != false
        val text = root.get("response")?.asString?.trim()
            ?: root.get("text")?.asString?.trim()
            ?: ""
        if (text.isBlank()) {
            val message = root.get("message")?.asString?.trim().orEmpty()
            throw IllegalStateException(
                message.ifBlank { "视觉分析无结果" },
            )
        }
        if (!success) {
            val message = root.get("message")?.asString?.trim().orEmpty()
            throw IllegalStateException(
                message.ifBlank { "视觉分析失败" },
            )
        }
        Log.i(TAG, "视觉分析成功 len=${text.length}")
        return VisionExplainResult(
            success = true,
            response = text,
            rawJson = raw,
        )
    }

    fun buildToolCallResult(description: String): JsonObject {
        val content = com.google.gson.JsonArray()
        content.add(
            JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", description)
            },
        )
        return JsonObject().apply {
            add("content", content)
            addProperty("isError", false)
        }
    }
}
