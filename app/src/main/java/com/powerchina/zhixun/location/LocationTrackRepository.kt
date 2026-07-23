package com.powerchina.zhixun.location

import java.util.Locale
import kotlin.math.roundToInt

object LocationTrackRepository {

    fun formatCoordinate(lat: Double, lng: Double): String {
        return "${"%.6f".format(Locale.US, lat)} ${"%.6f".format(Locale.US, lng)}"
    }

    fun formatSpeedMps(speedMps: Float): String {
        val value = (speedMps * 10).roundToInt() / 10.0
        return "${value}m/s"
    }
}
