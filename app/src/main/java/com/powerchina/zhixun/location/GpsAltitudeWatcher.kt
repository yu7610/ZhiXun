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
import kotlin.math.abs

/**
 * 进入定位页后监听系统 GPS，补充百度网络定位通常缺失的海拔。
 */
class GpsAltitudeWatcher(
    context: Context,
    private val onAltitude: (Double) -> Unit,
) {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val handlerThread = HandlerThread("GpsAltitude").apply { start() }
    private val gpsHandler = Handler(handlerThread.looper)
    private var running = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            publishIfValid(location, location.provider ?: "gps")
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        var subscribed = false
        for (provider in providers) {
            if (!locationManager.isProviderEnabled(provider)) {
                Log.i(TAG, "provider=$provider 未启用")
                continue
            }
            val cached = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            if (cached != null) {
                publishIfValid(cached, "$provider 缓存")
            } else {
                Log.i(TAG, "provider=$provider 暂无缓存位置")
            }
            locationManager.requestLocationUpdates(
                provider,
                1000L,
                0f,
                listener,
                gpsHandler.looper,
            )
            subscribed = true
        }
        if (!subscribed) {
            Log.w(TAG, "GPS 未开启，无法获取实时海拔")
        } else {
            Log.i(TAG, "GPS 海拔监听已启动")
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { locationManager.removeUpdates(listener) }
        handlerThread.quitSafely()
        Log.i(TAG, "GPS 海拔监听已停止")
    }

    private fun publishIfValid(location: Location, source: String) {
        val altitude = location.altitude
        if (!altitude.isFinite() || altitude !in MIN_ALTITUDE_M..MAX_ALTITUDE_M) return
        if (abs(altitude) < 0.1 && !location.hasAltitude()) return
        Log.i(TAG, "$source 海拔=${altitude.toInt()}m")
        onAltitude(altitude)
    }

    companion object {
        private const val TAG = BaiduLocationReporter.TAG
        private const val MIN_ALTITUDE_M = -500.0
        private const val MAX_ALTITUDE_M = 9000.0
    }
}
