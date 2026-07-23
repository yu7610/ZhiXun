package com.powerchina.zhixun.location

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.powerchina.zhixun.xiaozhi.XiaozhiVisionClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class GeofencesByDevicesResult(
    val code: Int?,
    val msg: String?,
    /** 接口返回的围栏 data（JSON 原文） */
    val data: String?,
    val raw: String,
    val fences: List<FenceArea> = emptyList(),
)

/**
 * 按设备查询围栏：POST /api/AIEngineer/geofences/byDevices
 *
 * 使用首页已保存的 generateToken data 作为 token，再带 header + 设备号 body 请求本接口。
 */
object GeofenceApi {

    private const val TAG = "GeofenceApi"
    const val BASE_URL = OpenAppFenceApi.BASE_URL
    const val BY_DEVICES_URL = "$BASE_URL/api/AIEngineer/geofences/byDevices"

    private val gson = Gson()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * 点击围栏：用首页 token → geofences/byDevices。
     */
    fun fetchForDevice(context: Context, terCode: String): Result<GeofencesByDevicesResult> {
        val token = runCatching { OpenAppFenceApi.requireSavedToken(context) }
            .getOrElse { return Result.failure(it) }
        Log.i(TAG, "使用首页 token 请求 geofences/byDevices")
        return fetchByDevices(context, listOf(terCode), token)
    }

    fun fetchByDevices(
        context: Context,
        terCodes: List<String>,
        token: String,
    ): Result<GeofencesByDevicesResult> = runCatching {
        val devices = terCodes
            .map { XiaozhiVisionClient.normalizeMacWithColons(it) }
            .filter { it.isNotBlank() }
            .distinct()
        require(devices.isNotEmpty()) { "未配置设备编号" }
        require(token.isNotBlank()) { "token 为空" }

        val bodyJson = gson.toJson(
            mapOf("terCodes" to devices),
        )
        Log.i(TAG, "POST $BY_DEVICES_URL body=$bodyJson")

        val request = Request.Builder()
            .url(BY_DEVICES_URL)
            .post(bodyJson.toRequestBody(jsonMedia))
            .header("Content-Type", "application/json")
            .header("token", token)
            .header("Authorization", "Bearer $token")
            .build()

        OpenAppFenceApi.httpClient(context).newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            Log.i(TAG, "geofences/byDevices HTTP ${response.code} 响应: $raw")
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: $raw")
            }
            val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            val code = json?.get("code")?.asInt
            val msg = json?.get("msg")?.takeIf { !it.isJsonNull }?.asString
            val dataElement = json?.get("data")?.takeIf { !it.isJsonNull }
            val data = dataElement?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            if (code != null && code != 200 && code != 0) {
                throw IllegalStateException("业务错误 code=$code: $raw")
            }
            val fences = parseFences(dataElement)
            Log.i(TAG, "解析围栏 ${fences.size} 个: ${fences.map { "${it.id}/${it.name}/${it.points.size}pts" }}")
            GeofencesByDevicesResult(code = code, msg = msg, data = data, raw = raw, fences = fences)
        }
    }.onFailure { e ->
        Log.w(TAG, "geofences/byDevices 失败", e)
    }

    /**
     * 解析 data：GeoJSON Polygon，coordinates 为 [lng, lat]。
     */
    fun parseFences(dataElement: JsonElement?): List<FenceArea> {
        if (dataElement == null || dataElement.isJsonNull) return emptyList()
        val list: JsonArray = when {
            dataElement.isJsonArray -> dataElement.asJsonArray
            dataElement.isJsonObject -> JsonArray().also { it.add(dataElement) }
            else -> return emptyList()
        }
        return list.mapNotNull { el ->
            runCatching { parseOneFence(el.asJsonObject) }.getOrElse { e ->
                Log.w(TAG, "解析单条围栏失败: $el", e)
                null
            }
        }
    }

    private fun parseOneFence(obj: JsonObject): FenceArea? {
        val geometry = obj.getAsJsonObject("geometry") ?: return null
        val type = geometry.get("type")?.asString.orEmpty()
        val points = when (type.lowercase()) {
            "polygon" -> parsePolygonRing(geometry.getAsJsonArray("coordinates"))
            "point" -> {
                val coords = geometry.getAsJsonArray("coordinates") ?: return null
                if (coords.size() < 2) return null
                // GeoJSON Point: [lng, lat]
                listOf(TrackPoint(latitude = coords[1].asDouble, longitude = coords[0].asDouble))
            }
            else -> emptyList()
        }
        if (points.size < 3 && type.equals("Polygon", ignoreCase = true)) return null
        if (points.isEmpty()) return null
        val idEl = obj.get("id")
        val id = when {
            idEl == null || idEl.isJsonNull -> "fence_${points.hashCode()}"
            idEl.isJsonPrimitive && idEl.asJsonPrimitive.isNumber -> idEl.asNumber.toString()
            idEl.isJsonPrimitive && idEl.asJsonPrimitive.isString -> idEl.asString
            else -> idEl.toString()
        }
        val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        return FenceArea(id = id, name = name, points = points)
    }

    /** GeoJSON Polygon coordinates: [[[lng,lat],...]] 取外环 */
    private fun parsePolygonRing(coordinates: JsonArray?): List<TrackPoint> {
        if (coordinates == null || coordinates.size() == 0) return emptyList()
        val ring = coordinates[0].asJsonArray
        val points = mutableListOf<TrackPoint>()
        for (i in 0 until ring.size()) {
            val pair = ring[i].asJsonArray
            if (pair.size() < 2) continue
            val lng = pair[0].asDouble
            val lat = pair[1].asDouble
            points.add(TrackPoint(latitude = lat, longitude = lng))
        }
        // 去掉首尾重复闭合点，百度 Polygon 自行闭合
        if (points.size >= 2) {
            val first = points.first()
            val last = points.last()
            if (first.latitude == last.latitude && first.longitude == last.longitude) {
                points.removeAt(points.lastIndex)
            }
        }
        return points
    }
}
