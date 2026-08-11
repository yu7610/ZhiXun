package com.powerchina.zhixun.location

import java.util.Locale
import kotlin.math.roundToInt

object LocationTrackRepository {

    fun formatCoordinate(lat: Double, lng: Double): String {
        return "${"%.6f".format(Locale.US, lat)} ${"%.6f".format(Locale.US, lng)}"
    }

    /** 附带定位来源与精度，避免把网络±40m 当成“点位误差” */
    fun formatCoordinateWithAccuracy(
        lat: Double,
        lng: Double,
        isGnss: Boolean,
        radiusM: Float,
    ): String {
        val base = formatCoordinate(lat, lng)
        val source = if (isGnss) "GPS" else "网络"
        return if (radiusM > 0f) {
            "$base · $source±${radiusM.roundToInt()}m"
        } else {
            "$base · $source"
        }
    }

    fun formatSpeedMps(speedMps: Float): String {
        val value = (speedMps * 10).roundToInt() / 10.0
        return "${value}m/s"
    }
}
