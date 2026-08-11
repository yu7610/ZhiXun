package com.powerchina.zhixun.dashcam

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import com.powerchina.zhixun.location.OpenAppFenceApi
import com.powerchina.zhixun.xiaozhi.XiaozhiVisionClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/**
 * 上传 MP4 录像片段到 AI 工程师平台。
 * POST https://111.231.8.58:18099/api/AIEngineer/uploadVideo
 */
object DashcamVideoClipUploader {

    const val TAG = "DashcamVideoUpload"
    private const val UPLOAD_VIDEO_URL =
        "${OpenAppFenceApi.BASE_URL}/api/AIEngineer/uploadVideo"

    data class UploadResult(
        val code: Int?,
        val msg: String?,
        val videoUrl: String?,
        val raw: String,
    )

    fun upload(
        context: Context,
        videoFile: File,
        terCode: String,
        durationSec: Int,
        recordTimeMs: Long = videoFile.lastModified().takeIf { it > 0L }
            ?: System.currentTimeMillis(),
        latitude: Double? = null,
        longitude: Double? = null,
    ): Result<UploadResult> = runCatching {
        require(videoFile.exists() && videoFile.length() > 0L) { "录像文件为空" }
        val deviceCode = XiaozhiVisionClient.normalizeMacWithColons(terCode)
        require(deviceCode.isNotBlank()) { "未配置设备编号" }
        val duration = durationSec.coerceAtLeast(1).toString()
        val recordTime = formatRecordTime(recordTimeMs)

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("duration", duration)
            .addFormDataPart("terCode", deviceCode)
            .addFormDataPart("recordTime", recordTime)
            .addFormDataPart(
                "file",
                videoFile.name.ifBlank { "record.mp4" },
                videoFile.asRequestBody("video/mp4".toMediaType()),
            )
        latitude?.let {
            bodyBuilder.addFormDataPart(
                "latitude",
                String.format(Locale.US, "%.6f", it.coerceIn(-90.0, 90.0)),
            )
        }
        longitude?.let {
            bodyBuilder.addFormDataPart(
                "longitude",
                String.format(Locale.US, "%.6f", it.coerceIn(-180.0, 180.0)),
            )
        }

        val requestBuilder = Request.Builder()
            .url(UPLOAD_VIDEO_URL)
            .post(bodyBuilder.build())
        OpenAppFenceApi.savedToken(context)?.takeIf { it.isNotBlank() }?.let { token ->
            requestBuilder.header("token", token)
            requestBuilder.header("Authorization", "Bearer $token")
        }

        Log.i(
            TAG,
            "POST $UPLOAD_VIDEO_URL terCode=$deviceCode duration=${duration}s " +
                "recordTime=$recordTime file=${videoFile.name} size=${videoFile.length()}B " +
                "lat=$latitude lng=$longitude",
        )

        // 大文件上传：复用信任自签证书的客户端，并放宽超时
        val client = OpenAppFenceApi.httpClient(context).newBuilder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        client.newCall(requestBuilder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            Log.i(TAG, "uploadVideo HTTP ${response.code} 响应: ${raw.take(800)}")
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: $raw")
            }
            val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            val code = json?.get("code")?.asInt
            val msg = json?.get("msg")?.takeIf { !it.isJsonNull }?.asString
            if (code != null && code != 200 && code != 0) {
                throw IllegalStateException("业务错误 code=$code: $raw")
            }
            val videoUrl = json?.getAsJsonObject("data")
                ?.get("video")
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            UploadResult(code = code, msg = msg, videoUrl = videoUrl, raw = raw)
        }
    }.onFailure { e ->
        Log.w(TAG, "uploadVideo 失败: ${videoFile.name}", e)
    }

    private fun formatRecordTime(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        return fmt.format(Date(epochMs))
    }
}
