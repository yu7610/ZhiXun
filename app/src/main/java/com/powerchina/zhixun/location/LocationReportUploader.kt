package com.powerchina.zhixun.location

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import com.powerchina.zhixun.xiaozhi.XiaozhiVisionClient
import okhttp3.FormBody
import okhttp3.Request

data class ReceiveLocationResult(
    val code: Int?,
    val msg: String?,
    /** 接口返回的定位/风险描述文案 */
    val data: String?,
    val raw: String,
)

/**
 * 定时上报设备经纬度：POST /api/AIEngineer/receiveLocation
 *
 * 使用首页 [OpenAppFenceApi.fetchAndSaveTokenOnHome] 已保存的 token。
 */
object LocationReportUploader {

    private const val TAG = "LocationReport"
    const val BASE_URL = OpenAppFenceApi.BASE_URL
    const val RECEIVE_LOCATION_URL = "$BASE_URL/api/AIEngineer/receiveLocation"

    fun report(
        context: Context,
        latitude: Double,
        longitude: Double,
        terCode: String,
        timestampSec: Long = System.currentTimeMillis() / 1000L,
    ): Result<ReceiveLocationResult> = runCatching {
        val token = OpenAppFenceApi.requireSavedToken(context)
        val deviceCode = XiaozhiVisionClient.normalizeMacWithColons(terCode)
        require(deviceCode.isNotBlank()) { "未配置设备编号" }
        val body = FormBody.Builder()
            .add("latitude", String.format(java.util.Locale.US, "%.6f", latitude))
            .add("longitude", String.format(java.util.Locale.US, "%.6f", longitude))
            .add("terCode", deviceCode)
            .add("timestamp", timestampSec.toString())
            .build()

        Log.i(
            TAG,
            "POST $RECEIVE_LOCATION_URL " +
                "latitude=${latitude} longitude=${longitude} terCode=$deviceCode timestamp=$timestampSec",
        )

        val request = Request.Builder()
            .url(RECEIVE_LOCATION_URL)
            .post(body)
            .header("token", token)
            .header("Authorization", "Bearer $token")
            .build()

        OpenAppFenceApi.httpClient(context).newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            Log.i(TAG, "receiveLocation HTTP ${response.code} 响应: $raw")
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: $raw")
            }
            val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            val code = json?.get("code")?.asInt
            val msg = json?.get("msg")?.takeIf { !it.isJsonNull }?.asString
            val data = json?.get("data")?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }
            if (code != null && code != 200 && code != 0) {
                throw IllegalStateException("业务错误 code=$code: $raw")
            }
            ReceiveLocationResult(code = code, msg = msg, data = data, raw = raw)
        }
    }.onFailure { e ->
        Log.w(TAG, "receiveLocation 失败", e)
    }
}
