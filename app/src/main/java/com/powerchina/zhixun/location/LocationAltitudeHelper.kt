package com.powerchina.zhixun.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import com.baidu.location.BDLocation
import kotlin.math.abs

object LocationAltitudeHelper {

    private const val MIN_ALTITUDE_M = -500.0
    private const val MAX_ALTITUDE_M = 9000.0

    fun fromBaiduLocation(location: BDLocation): Double? {
        val altitude = location.altitude
        if (!altitude.isFinite() || altitude !in MIN_ALTITUDE_M..MAX_ALTITUDE_M) return null
        if (location.hasAltitude()) return altitude
        val isGnss = location.locType == BDLocation.TypeGpsLocation ||
            location.locType == BDLocation.TypeGnssLocation
        if (isGnss && abs(altitude) > 0.1) return altitude
        if (abs(altitude) > 0.1) return altitude
        return null
    }

    @SuppressLint("MissingPermission")
    fun fromSystemGps(context: Context): Double? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
        for (provider in providers) {
            if (!manager.isProviderEnabled(provider)) continue
            val location = runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                ?: continue
            val altitude = location.altitude
            if (location.hasAltitude() && altitude.isFinite() && altitude in MIN_ALTITUDE_M..MAX_ALTITUDE_M) {
                return altitude
            }
        }
        return null
    }

    fun resolve(context: Context, location: BDLocation, cached: Double?): Double? {
        return fromBaiduLocation(location)
            ?: fromSystemGps(context)
            ?: cached
    }

    fun format(altitudeM: Double?): String {
        if (altitudeM == null || !altitudeM.isFinite()) return "--"
        return "${altitudeM.toInt()}m"
    }
}
