package com.powerchina.zhixun.dashcam

import android.content.Context
import android.util.Log
import com.powerchina.zhixun.network.OkHttpClientFactory
import com.powerchina.zhixun.xiaozhi.XiaozhiVisionClient
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/**
 * 拍照 + 语音说明确认后，上传标记图片到 AI 工程师平台。
 */
object DashcamMarkedImgUploader {

    private val logTag get() = DashcamAsrUploader.TAG
    const val BASE_URL = "http://111.231.8.58:18089"
    const val UPLOAD_MARKED_IMG_URL = "$BASE_URL/api/AIEngineer/uploadMarkedImg"

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

    fun upload(
        context: Context,
        photoFile: File,
        markText: String,
        terCode: String,
    ): Result<String> = runCatching {
        require(photoFile.exists() && photoFile.length() > 0L) { "照片文件为空" }
        require(markText.isNotBlank()) { "标记文字为空" }
        val deviceCode = XiaozhiVisionClient.normalizeMacWithColons(terCode)
        require(deviceCode.isNotBlank()) { "未配置设备编号" }

        val mime = when (photoFile.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("markText", markText)
            .addFormDataPart("terCode", deviceCode)
            .addFormDataPart(
                "photo",
                photoFile.name,
                photoFile.asRequestBody(mime.toMediaType()),
            )
            .build()

        Log.i(
            logTag,
            "POST $UPLOAD_MARKED_IMG_URL markText=$markText terCode=$deviceCode " +
                "photo=${photoFile.absolutePath} size=${photoFile.length()}B",
        )

        val request = Request.Builder()
            .url(UPLOAD_MARKED_IMG_URL)
            .post(body)
            .build()

        client(context).newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            Log.i(logTag, "uploadMarkedImg HTTP ${response.code} 响应: $raw")
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: $raw")
            }
            raw
        }
    }.onFailure { e ->
        Log.e(logTag, "uploadMarkedImg 失败", e)
    }
}
