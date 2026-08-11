package com.powerchina.zhixun.location

import android.util.Log
import com.baidu.location.BDLocation
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 定位结果轻量过滤：GPS 优先、网络保底、静止抗飘、轨迹节流。
 */
object LocationQualityFilter {

    private const val TAG = BaiduLocationReporter.TAG

    private const val MAX_GPS_RADIUS_M = 40f
    private const val MAX_NETWORK_RADIUS_M = 80f
    private const val MAX_SPEED_MPS = 40.0
    private const val JUMP_RADIUS_FACTOR = 1.5
    private const val MAX_CONSECUTIVE_REJECTS = 3
    private const val EMA_GPS = 0.8
    /** 网络点平滑更保守，减少室内 WiFi 漂移 */
    private const val EMA_NETWORK = 0.2
    private const val GPS_STATIONARY_M = 5.0
    /** 网络点至少钉住这么远；实际还取 max(本值, 当前/上次精度圈) */
    private const val NETWORK_STATIONARY_M = 25.0
    private const val TRACK_GPS_MIN_M = 3.0
    private const val TRACK_NETWORK_MIN_M = 20.0
    /** 有 GNSS 后一段时间内忽略网络点 */
    private const val GPS_PRIORITY_MS = 45_000L

    private var hasAccepted = false
    private var lastRawLat = 0.0
    private var lastRawLng = 0.0
    private var lastSmoothLat = 0.0
    private var lastSmoothLng = 0.0
    private var lastTimeMs = 0L
    private var lastRadiusM = 20f
    private var lastIsGnss = false
    private var consecutiveRejects = 0
    private var stationaryHoldCount = 0

    @Volatile
    var lastFixIsGnss: Boolean = false
        private set

    fun reset() {
        hasAccepted = false
        consecutiveRejects = 0
        stationaryHoldCount = 0
        lastTimeMs = 0L
        lastFixIsGnss = false
        lastIsGnss = false
    }

    fun filter(location: BDLocation): BDLocation? {
        if (!BaiduLocationReporter.isValid(location)) return null

        if (isCacheOrOffline(location)) {
            Log.w(TAG, "丢弃缓存/离线点 type=${location.locType}")
            return null
        }

        val isGnss = isGnssFix(location)
        val isNetwork = location.locType == BDLocation.TypeNetWorkLocation
        val radius = location.radius
        val now = System.currentTimeMillis()
        val lat = location.latitude
        val lng = location.longitude

        if (!isGnss && lastIsGnss && hasAccepted && now - lastTimeMs < GPS_PRIORITY_MS) {
            Log.i(TAG, "已有GPS，忽略网络点 type=${location.locType}")
            return null
        }

        if (radius > 0f) {
            val maxRadius = if (isGnss) MAX_GPS_RADIUS_M else MAX_NETWORK_RADIUS_M
            if (radius > maxRadius) {
                Log.w(TAG, "丢弃低精度点 type=${location.locType} radius=${radius}m > ${maxRadius}m")
                consecutiveRejects++
                return null
            }
        }

        // 网络定位精度圈常 40m+：圈内抖动视为静止，避免室内点位被噪声拽走
        val stationaryLimit = if (isGnss) {
            GPS_STATIONARY_M
        } else {
            maxOf(
                NETWORK_STATIONARY_M,
                radius.coerceAtLeast(0f).toDouble(),
                if (hasAccepted) lastRadiusM.toDouble() else 0.0,
            )
        }

        if (hasAccepted) {
            val distFromKeep = haversineMeters(lastSmoothLat, lastSmoothLng, lat, lng)
            if (distFromKeep < stationaryLimit) {
                stationaryHoldCount++
                lastTimeMs = now
                // hold 期间不缩小精度圈，否则网络锁定阈值会越来越松
                consecutiveRejects = 0
                location.latitude = lastSmoothLat
                location.longitude = lastSmoothLng
                if (stationaryHoldCount == 1 || stationaryHoldCount % 5 == 0) {
                    Log.i(
                        TAG,
                        "静止 hold#$stationaryHoldCount gnss=$isGnss " +
                            "dist=${"%.1f".format(distFromKeep)}m < ${"%.1f".format(stationaryLimit)}m " +
                            "keep=($lastSmoothLat,$lastSmoothLng)",
                    )
                }
                return location
            }

            val dtSec = ((now - lastTimeMs).coerceAtLeast(1L)) / 1000.0
            val distRaw = haversineMeters(lastRawLat, lastRawLng, lat, lng)
            val radiusBudget = (lastRadiusM + radius.coerceAtLeast(0f)) * JUMP_RADIUS_FACTOR
            val maxJumpM = MAX_SPEED_MPS * dtSec + radiusBudget.coerceAtLeast(10.0)
            if (distRaw > maxJumpM) {
                if (lastIsGnss && !isGnss) {
                    Log.w(TAG, "丢弃网络跳点 dist=${"%.1f".format(distRaw)}m")
                    consecutiveRejects++
                    return null
                }
                if (consecutiveRejects < MAX_CONSECUTIVE_REJECTS) {
                    Log.w(
                        TAG,
                        "丢弃异常跳点 dist=${"%.1f".format(distRaw)}m reject#${consecutiveRejects + 1}",
                    )
                    consecutiveRejects++
                    return null
                }
            }
        }

        val alpha = when {
            !hasAccepted -> 1.0
            isGnss -> EMA_GPS
            else -> EMA_NETWORK
        }
        val smoothLat = if (hasAccepted) {
            lastSmoothLat + alpha * (lat - lastSmoothLat)
        } else {
            lat
        }
        val smoothLng = if (hasAccepted) {
            lastSmoothLng + alpha * (lng - lastSmoothLng)
        } else {
            lng
        }

        location.latitude = smoothLat
        location.longitude = smoothLng

        hasAccepted = true
        lastRawLat = lat
        lastRawLng = lng
        lastSmoothLat = smoothLat
        lastSmoothLng = smoothLng
        lastTimeMs = now
        lastRadiusM = if (radius > 0f) radius else lastRadiusM
        lastIsGnss = isGnss
        lastFixIsGnss = isGnss
        consecutiveRejects = 0
        stationaryHoldCount = 0

        Log.i(
            TAG,
            "采纳定位 gnss=$isGnss type=${location.locType} radius=${radius}m " +
                "raw=($lat,$lng) smooth=($smoothLat,$smoothLng)" +
                if (isNetwork) "（网络保底）" else "",
        )
        return location
    }

    fun shouldAppendTrack(from: TrackPoint?, toLat: Double, toLng: Double): Boolean {
        if (from == null) return true
        val minM = if (lastFixIsGnss) TRACK_GPS_MIN_M else TRACK_NETWORK_MIN_M
        return haversineMeters(from.latitude, from.longitude, toLat, toLng) >= minM
    }

    fun isGnssFix(location: BDLocation): Boolean =
        when (location.locType) {
            BDLocation.TypeGpsLocation,
            BDLocation.TypeGnssLocation,
            BDLocation.TYPE_HD_LOCATION,
            BDLocation.TYPE_BMS_HD_LOCATION,
            BDLocation.TYPE_LANE_HD_LOCATION,
            -> true
            else -> false
        }

    private fun isCacheOrOffline(location: BDLocation): Boolean =
        when (location.locType) {
            BDLocation.TypeOffLineLocation,
            BDLocation.TypeCacheLocation,
            65, 67,
            -> true
            else -> false
        }

    private fun haversineMeters(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
    ): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2).pow(2)
        val c = 2 * asin(min(1.0, sqrt(a)))
        return r * c
    }
}
