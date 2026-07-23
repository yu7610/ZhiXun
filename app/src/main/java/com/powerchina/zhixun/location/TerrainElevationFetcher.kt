package com.powerchina.zhixun.location

import android.util.Log
import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 根据经纬度查询地形海拔（Open-Meteo），用于 GPS/网络定位无海拔时的界面展示。
 */
object TerrainElevationFetcher {

    private const val TAG = BaiduLocationReporter.TAG
    private const val BASE_URL = "https://api.open-meteo.com/v1/elevation"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, Double>()

    suspend fun fetch(latitude: Double, longitude: Double): Result<Double> = withContext(Dispatchers.IO) {
        runCatching {
            val key = "%.4f,%.4f".format(latitude, longitude)
            cache[key]?.let { return@runCatching it }
            val url = "$BASE_URL?latitude=$latitude&longitude=$longitude"
            Log.i(TAG, "GET $url")
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}: $raw")
                }
                val elevation = JsonParser.parseString(raw)
                    .asJsonObject
                    .getAsJsonArray("elevation")
                    .first()
                    .asDouble
                if (!elevation.isFinite() || elevation !in -500.0..9000.0) {
                    throw IllegalStateException("无效海拔: $elevation")
                }
                cache[key] = elevation
                Log.i(TAG, "地形海拔 elev=${elevation.toInt()}m")
                elevation
            }
        }.onFailure { e ->
            Log.w(TAG, "地形海拔获取失败", e)
        }
    }
}
