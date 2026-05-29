package com.powerchina.zhixun.location

import android.location.Location
import android.location.LocationManager
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.utils.CoordinateConverter

object LocationCoordinateConverter {

    fun toBd09(location: Location): LatLng? {
        if (location.latitude == 0.0 && location.longitude == 0.0) return null
        val source = when (location.provider) {
            LocationManager.GPS_PROVIDER -> CoordinateConverter.CoordType.GPS
            else -> CoordinateConverter.CoordType.COMMON
        }
        return runCatching {
            CoordinateConverter()
                .from(source)
                .coord(LatLng(location.latitude, location.longitude))
                .convert()
        }.getOrNull()
    }
}
