package com.powerchina.zhixun.xiaozhi

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.powerchina.zhixun.network.OkHttpClientFactory
import com.powerchina.zhixun.physicalkey.PhotoKeyLog
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
 * 基址：http://8.134.202.195:8001/
 */
object XiaozhiVisionClient {

    private const val TAG = PhotoKeyLog.TAG
    const val BASE_URL = "http://8.134.202.195:8001"
    /** 兼容旧调用：常规检测走 /detect/xiaozhi */
    const val DETECT_IMAGE_URL = "$BASE_URL/detect/xiaozhi"
    private const val NO_HAZARD_TEXT = "无安全隐患"

    @Volatile
    private var httpClient: OkHttpClient? = null

    private fun client(context: Context): OkHttpClient {
        return httpClient ?: synchronized(this) {
            httpClient ?: OkHttpClientFactory.create(
                context = context.applicationContext,
                connectTimeoutSec = 15,
                readTimeoutSec = 90,
                writeTimeoutSec = 60,
            ).also { httpClient = it }
        }
    }

    fun detectImageFile(
        context: Context,
        deviceId: String,
        jpegBytes: ByteArray,
        filename: String,
        kind: VisionCheckKind = VisionCheckKind.NORMAL,
    ): Result<VisionExplainResult> = runCatching {
        val mac = normalizeMacWithColons(deviceId)
        require(mac.isNotBlank()) { "未配置设备 MAC 地址" }
        val safeName = filename.ifBlank { "photo.jpg" }

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image",
                safeName,
                jpegBytes.toRequestBody("image/jpeg".toMediaType()),
            )

        val url = when (kind) {
            VisionCheckKind.NORMAL -> {
                bodyBuilder.addFormDataPart("deviceId", mac)
                bodyBuilder.addFormDataPart("uploadOnViolation", "true")
                "$BASE_URL/detect/xiaozhi"
            }
            VisionCheckKind.SHATOUJIAO -> {
                bodyBuilder.addFormDataPart("project_id", "shatoujiao")
                "$BASE_URL/detect"
            }
            VisionCheckKind.ENTRANCE -> {
                bodyBuilder.addFormDataPart("project_id", "entrance")
                "$BASE_URL/detect"
            }
            VisionCheckKind.WEARABLE -> {
                bodyBuilder.addFormDataPart("project_id", "wearable")
                "$BASE_URL/detect"
            }
            VisionCheckKind.FIRSTAID -> {
                bodyBuilder.addFormDataPart("project_id", "firstaid")
                "$BASE_URL/detect"
            }
        }

        Log.i(
            TAG,
            "POST detect kind=$kind url=$url deviceId=$mac file=$safeName jpeg=${jpegBytes.size}B",
        )

        val request = Request.Builder()
            .url(url)
            .post(bodyBuilder.build())
            .build()

        client(context).newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            logResponseBody(response.code, kind, raw)
            if (response.code != 200) {
                throw IllegalStateException("隐患检测 HTTP ${response.code}: $raw")
            }
            parseDetectResponse(raw, safeName, kind)
        }
    }.onFailure { e ->
        Log.e(TAG, "隐患检测失败 kind=$kind", e)
    }

    /** logcat 单行约 4KB，分段打印完整返回体 */
    private fun logResponseBody(code: Int, kind: VisionCheckKind, raw: String) {
        Log.i(TAG, "拍照上传接口 HTTP $code kind=$kind 返回长度=${raw.length}")
        Log.i("shuoyu", "拍照上传接口 HTTP $code kind=$kind 返回长度=${raw.length}")
        if (raw.isEmpty()) {
            Log.i(TAG, "拍照上传接口返回: <empty>")
            Log.i("shuoyu", "拍照上传接口返回: <empty>")
            return
        }
        val chunkSize = 3500
        if (raw.length <= chunkSize) {
            Log.i(TAG, "拍照上传接口返回: $raw")
            Log.i("shuoyu", "拍照上传接口返回: $raw")
            return
        }
        var index = 0
        var part = 1
        while (index < raw.length) {
            val end = (index + chunkSize).coerceAtMost(raw.length)
            val chunk = raw.substring(index, end)
            Log.i(TAG, "拍照上传接口返回[$part]: $chunk")
            Log.i("shuoyu", "拍照上传接口返回[$part]: $chunk")
            index = end
            part++
        }
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

    private fun parseDetectResponse(
        raw: String,
        filename: String,
        kind: VisionCheckKind,
    ): VisionExplainResult {
        val text = extractHazardText(raw).ifBlank { NO_HAZARD_TEXT }
        val responseText = JsonObject().apply {
            addProperty("success", true)
            addProperty("filename", filename)
            addProperty("check", kind.name.lowercase())
            addProperty("text", text)
        }.toString()
        Log.i(TAG, "隐患检测解析 kind=$kind text=$text")
        return VisionExplainResult(
            success = true,
            response = responseText,
            rawJson = raw,
        )
    }

    /**
     * 从检测原始 JSON 提取可播报文案；无隐患或空则 null。
     * 供录屏帧上传等与 MCP 共用同一套解析。
     */
    fun speakTextFromDetectRaw(raw: String): String? {
        val text = extractHazardText(raw).trim()
        return if (text.isBlank() || text == NO_HAZARD_TEXT) null else text
    }

    /**
     * 兼容：
     * - /detect/xiaozhi：is_violation + violations[{violation_name}]
     * - /detect：violations 列表
     * - 旧 recoder：data.text
     */
    private fun extractHazardText(raw: String): String = runCatching {
        val root = JsonParser.parseString(raw).asJsonObject
        root.getAsJsonObject("data")?.get("text")?.takeIf { !it.isJsonNull }?.asString?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return@runCatching it }

        val violations = root.getAsJsonArray("violations")
        if (violations != null && violations.size() > 0) {
            val names = violations.mapNotNull { el ->
                val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                obj.get("violation_name")?.asString?.trim()?.ifBlank { null }
                    ?: obj.get("name")?.asString?.trim()?.ifBlank { null }
                    ?: obj.get("description")?.asString?.trim()?.ifBlank { null }
            }
            if (names.isNotEmpty()) return@runCatching names.joinToString("；")
        }

        val isViolation = root.get("is_violation")?.asBoolean == true
        if (isViolation) return@runCatching "检测到安全隐患"
        ""
    }.getOrDefault("")

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
        val content = JsonArray()
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

    fun buildPlainToolResult(text: String): JsonObject {
        val content = JsonArray()
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
