package com.powerchina.zhixun.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.baidu.location.BDAbstractLocationListener
import com.baidu.location.BDLocation
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption

data class LiveLocationSample(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speedMps: Float,
    val timestampMs: Long = System.currentTimeMillis(),
)

class RealtimeLocationTracker(
    private val context: Context,
    private val onSample: (LiveLocationSample) -> Unit,
) {

    private var baiduClient: LocationClient? = null
    private var locationManager: LocationManager? = null
    private var started = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            pollLastKnownLocation()
            if (started) {
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    private val androidListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            baiduClient?.updateLocation(location)
            deliverAndroidLocation(location)
        }

        @Deprecated("Deprecated in API")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        started = true
        startBaidu()
        startAndroid()
        mainHandler.post(pollRunnable)
    }

    fun stop() {
        started = false
        mainHandler.removeCallbacks(pollRunnable)
        runCatching { baiduClient?.stop() }
        baiduClient = null
        runCatching { locationManager?.removeUpdates(androidListener) }
        locationManager = null
    }

    @SuppressLint("MissingPermission")
    private fun startBaidu() {
        runCatching {
            val client = LocationClient(context.applicationContext)
            val option = LocationClientOption().apply {
                setLocationMode(LocationClientOption.LocationMode.Hight_Accuracy)
                setCoorType("bd09ll")
                setScanSpan(POLL_INTERVAL_MS.toInt())
                setOpenGps(true)
                setIsNeedAltitude(true)
                setIsNeedLocationDescribe(false)
                setIgnoreKillProcess(false)
                setOnceLocation(false)
                setLocationNotify(true)
                setIsEnableBeidouMode(true)
                setOpenAutoNotifyMode(
                    POLL_INTERVAL_MS.toInt(),
                    1,
                    LocationClientOption.LOC_SENSITIVITY_HIGHT,
                )
            }
            client.locOption = option
            client.registerLocationListener(
                object : BDAbstractLocationListener() {
                    override fun onReceiveLocation(location: BDLocation?) {
                        if (location == null) return
                        if (!isValidBaiduLocation(location)) {
                            Log.w(TAG, "百度定位无效 type=${location.locType}")
                            return
                        }
                        deliverBaiduLocation(location)
                    }
                },
            )
            client.start()
            client.requestLocation()
            baiduClient = client
            pollLastKnownLocation()
            Log.i(TAG, "百度定位已启动")
        }.onFailure { error ->
            Log.e(TAG, "百度定位启动失败", error)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAndroid() {
        runCatching {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager = manager
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let(::feedAndroidLocation)
            manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let(::feedAndroidLocation)
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                POLL_INTERVAL_MS,
                0f,
                androidListener,
                Looper.getMainLooper(),
            )
            manager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                POLL_INTERVAL_MS,
                0f,
                androidListener,
                Looper.getMainLooper(),
            )
            Log.i(TAG, "系统定位已启动")
        }.onFailure { error ->
            Log.e(TAG, "系统定位启动失败", error)
        }
    }

    private fun feedAndroidLocation(location: Location) {
        baiduClient?.updateLocation(location)
        deliverAndroidLocation(location)
    }

    private fun pollLastKnownLocation() {
        val last = baiduClient?.lastKnownLocation ?: return
        if (isValidBaiduLocation(last)) {
            deliverBaiduLocation(last)
        }
        baiduClient?.requestLocation()
    }

    private fun deliverBaiduLocation(location: BDLocation) {
        deliverSample(
            LiveLocationSample(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                speedMps = baiduSpeedToMps(location),
            ),
        )
    }

    private fun deliverAndroidLocation(location: Location) {
        if (location.latitude == 0.0 && location.longitude == 0.0) return
        val current = baiduClient?.lastKnownLocation
        if (current != null && isValidBaiduLocation(current)) {
            deliverSample(
                LiveLocationSample(
                    latitude = current.latitude,
                    longitude = current.longitude,
                    altitude = if (location.hasAltitude()) location.altitude else current.altitude,
                    speedMps = if (location.hasSpeed()) {
                        location.speed.coerceAtLeast(0f)
                    } else {
                        baiduSpeedToMps(current)
                    },
                    timestampMs = location.time,
                ),
            )
        }
    }

    private fun deliverSample(sample: LiveLocationSample) {
        mainHandler.post { onSample(sample) }
    }

    private fun isValidBaiduLocation(location: BDLocation): Boolean {
        if (location.latitude == 0.0 && location.longitude == 0.0) return false
        return when (location.locType) {
            BDLocation.TypeGpsLocation,
            BDLocation.TypeGnssLocation,
            BDLocation.TypeNetWorkLocation,
            BDLocation.TypeOffLineLocation,
            BDLocation.TypeCacheLocation,
            BDLocation.TYPE_HD_LOCATION,
            BDLocation.TYPE_BMS_HD_LOCATION,
            -> true
            else -> false
        }
    }

    private fun baiduSpeedToMps(location: BDLocation): Float {
        if (!location.hasSpeed()) return 0f
        // 百度定位 SDK 返回 km/h，转换为 m/s。
        return (location.speed / 3.6f).coerceAtLeast(0f)
    }

    companion object {
        private const val TAG = "RealtimeLocation"
        private const val POLL_INTERVAL_MS = 1_000L
    }
}
