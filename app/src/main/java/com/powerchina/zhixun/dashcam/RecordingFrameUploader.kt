package com.powerchina.zhixun.dashcam

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import com.powerchina.zhixun.network.OkHttpClientFactory
import com.powerchina.zhixun.xiaozhi.XiaozhiVisionClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 录屏过程中定时上传预览帧到隐患检测接口。
 */
object RecordingFrameUploader {

    const val TAG = "shuoyu"
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

    fun uploadFrame(
        context: Context,
        deviceId: String,
        jpegBytes: ByteArray,
        filename: String,
    ): Result<String> = runCatching {
        val mac = XiaozhiVisionClient.normalizeMacWithColons(deviceId)
        require(mac.isNotBlank()) { "未配置设备 MAC 地址" }
        val safeName = filename.ifBlank { "frame.jpg" }
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
            "POST detectImageFile deviceId=$mac topic=$topic file=$safeName jpeg=${jpegBytes.size}B",
        )

        val request = Request.Builder()
            .url(XiaozhiVisionClient.DETECT_IMAGE_URL)
            .post(body)
            .build()

        client(context).newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            Log.i(TAG, "HTTP ${response.code} 响应: $raw")
            if (response.code != 200) {
                throw IllegalStateException("HTTP ${response.code}: $raw")
            }
            raw
        }
    }.onFailure { e ->
        Log.e(TAG, "录屏帧上传失败", e)
    }

    /** 从 HTTP 200 JSON 中提取需播报的 data.text；无隐患或空则返回 null */
    fun parseSpeakText(raw: String): String? = runCatching {
        val root = JsonParser.parseString(raw).asJsonObject
        val data = root.getAsJsonObject("data") ?: return null
        val element = data.get("text") ?: return null
        val text = when {
            element.isJsonNull -> ""
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> element.asString.trim()
            else -> element.toString().trim()
        }
        if (text.isBlank() || text == NO_HAZARD_TEXT) null else text
    }.getOrNull()
}
