package com.powerchina.zhixun.location

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import com.powerchina.zhixun.network.OkHttpClientFactory
import com.powerchina.zhixun.xiaozhi.XiaozhiVisionClient
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 定时上报设备经纬度：POST /api/AIEngineer/receiveLocation
 */
object LocationReportUploader {

    private const val TAG = "LocationReport"
    const val BASE_URL = "http://111.231.8.58:18089"
    const val RECEIVE_LOCATION_URL = "$BASE_URL/api/AIEngineer/receiveLocation"

    @Volatile
    private var httpClient: OkHttpClient? = null

    private fun client(context: Context): OkHttpClient {
        return httpClient ?: synchronized(this) {
            httpClient ?: OkHttpClientFactory.create(
                context = context.applicationContext,
                connectTimeoutSec = 15,
                readTimeoutSec = 30,
                writeTimeoutSec = 30,
            ).also { httpClient = it }
        }
    }

    fun report(
        context: Context,
        latitude: Double,
        longitude: Double,
        terCode: String,
        timestampSec: Long = System.currentTimeMillis() / 1000L,
    ): Result<String> = runCatching {
        val deviceCode = XiaozhiVisionClient.normalizeMacWithColons(terCode)
        require(deviceCode.isNotBlank()) { "未配置设备编号" }
        val body = FormBody.Builder()
            .add("latitude", String.format("%.6f", latitude))
            .add("longitude", String.format("%.6f", longitude))
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
            .build()

        client(context).newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            Log.i(TAG, "receiveLocation HTTP ${response.code} 响应: $raw")
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: $raw")
            }
            val code = runCatching {
                JsonParser.parseString(raw).asJsonObject.get("code")?.asInt
            }.getOrNull()
            if (code != null && code != 200 && code != 0) {
                throw IllegalStateException("业务错误 code=$code: $raw")
            }
            raw
        }
    }.onFailure { e ->
        Log.w(TAG, "receiveLocation 失败", e)
    }
}
