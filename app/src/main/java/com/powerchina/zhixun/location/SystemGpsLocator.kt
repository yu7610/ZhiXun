package com.powerchina.zhixun.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.baidu.location.BDLocation
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.utils.CoordinateConverter

/**
 * 系统 GPS 补充：WGS84 → BD09LL，交给百度定位分发链路。
 * 文档说明仅设备/弱卫星场景可用系统定位接口补强。
 */
class SystemGpsLocator(
    context: Context,
    private val onGpsLocation: (BDLocation) -> Unit,
) {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handlerThread = HandlerThread("SystemGps").apply { start() }
    private val gpsHandler = Handler(handlerThread.looper)
    private var running = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.provider != LocationManager.GPS_PROVIDER) return
            if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M) {
                Log.w(TAG, "系统GPS精度差 accuracy=${location.accuracy}m，跳过")
                return
            }
            val bd = toBdLocation(location) ?: return
            Log.i(
                TAG,
                "系统GPS accuracy=${location.accuracy}m lat=${bd.latitude} lng=${bd.longitude}",
            )
            onGpsLocation(bd)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) {
            Log.i(TAG, "系统GPS已开启 provider=$provider")
        }

        override fun onProviderDisabled(provider: String) {
            Log.w(TAG, "系统GPS已关闭 provider=$provider")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.w(TAG, "系统 GPS_PROVIDER 未开启")
            return
        }
        runCatching {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_M,
                listener,
                gpsHandler.looper,
            )
            Log.i(TAG, "系统 GPS 监听已启动 interval=${MIN_INTERVAL_MS}ms")
        }.onFailure {
            Log.e(TAG, "系统 GPS 监听启动失败", it)
        }
        runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }
            .getOrNull()
            ?.takeIf { System.currentTimeMillis() - it.time < CACHE_MAX_AGE_MS }
            ?.let { listener.onLocationChanged(it) }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { locationManager.removeUpdates(listener) }
        handlerThread.quitSafely()
        Log.i(TAG, "系统 GPS 监听已停止")
    }

    private fun toBdLocation(location: Location): BDLocation? {
        val converted = runCatching {
            CoordinateConverter()
                .from(CoordinateConverter.CoordType.GPS)
                .coord(LatLng(location.latitude, location.longitude))
                .convert()
        }.getOrNull() ?: return null

        return BDLocation().apply {
            latitude = converted.latitude
            longitude = converted.longitude
            locType = BDLocation.TypeGpsLocation
            if (location.hasAccuracy()) radius = location.accuracy
            if (location.hasAltitude()) altitude = location.altitude
            if (location.hasSpeed()) speed = location.speed * 3.6f
            if (location.hasBearing()) direction = location.bearing
            satelliteNumber = location.extras?.getInt("satellites", -1) ?: -1
        }
    }

    companion object {
        private const val TAG = BaiduLocationReporter.TAG
        private const val MIN_INTERVAL_MS = 1_000L
        private const val MIN_DISTANCE_M = 1f
        private const val MAX_ACCURACY_M = 40f
        private const val CACHE_MAX_AGE_MS = 30_000L
    }
}
