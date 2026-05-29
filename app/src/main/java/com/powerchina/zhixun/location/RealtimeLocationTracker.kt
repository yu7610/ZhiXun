package com.powerchina.zhixun.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
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
    val provider: String = "unknown",
)

class RealtimeLocationTracker(
    private val context: Context,
    private val onSample: (LiveLocationSample) -> Unit,
) {

    private var baiduClient: LocationClient? = null
    private var locationManager: LocationManager? = null
    private var started = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastDeliveredTimeMs = 0L

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
    }

    fun stop() {
        started = false
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
                setOpenGnss(true)
                setIsNeedAltitude(true)
                setIsNeedLocationDescribe(false)
                setIgnoreKillProcess(false)
                setOnceLocation(false)
                setLocationNotify(true)
                setIsEnableBeidouMode(true)
                setOpenAutoNotifyMode(
                    POLL_INTERVAL_MS.toInt(),
                    0,
                    LocationClientOption.LOC_SENSITIVITY_HIGHT,
                )
            }
            client.locOption = option
            client.registerLocationListener(
                object : BDAbstractLocationListener() {
                    override fun onReceiveLocation(location: BDLocation?) {
                        if (location == null || !isValidBaiduLocation(location)) return
                        deliverBaiduLocation(location)
                    }
                },
            )
            client.start()
            client.requestLocation()
            baiduClient = client
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
            val providers = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    manager.isProviderEnabled(LocationManager.FUSED_PROVIDER)
                ) {
                    add(LocationManager.FUSED_PROVIDER)
                }
                add(LocationManager.GPS_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
            }
            providers.forEach { provider ->
                manager.getLastKnownLocation(provider)?.let(::deliverAndroidLocation)
                manager.requestLocationUpdates(
                    provider,
                    POLL_INTERVAL_MS,
                    0f,
                    androidListener,
                    Looper.getMainLooper(),
                )
            }
            Log.i(TAG, "系统定位已启动 providers=$providers")
        }.onFailure { error ->
            Log.e(TAG, "系统定位启动失败", error)
        }
    }

    private fun deliverBaiduLocation(location: BDLocation) {
        val speedMps = when {
            location.hasSpeed() && location.speed > 0f -> location.speed / 3.6f
            else -> 0f
        }
        deliverSample(
            LiveLocationSample(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                speedMps = speedMps,
                provider = "baidu:${location.locType}",
            ),
        )
    }

    private fun deliverAndroidLocation(location: Location) {
        val bdLatLng = LocationCoordinateConverter.toBd09(location) ?: return
        val altitude = when {
            location.hasAltitude() && location.altitude != 0.0 -> location.altitude
            else -> Double.NaN
        }
        val speedMps = if (location.hasSpeed()) location.speed.coerceAtLeast(0f) else 0f
        deliverSample(
            LiveLocationSample(
                latitude = bdLatLng.latitude,
                longitude = bdLatLng.longitude,
                altitude = altitude,
                speedMps = speedMps,
                timestampMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
                provider = location.provider ?: "android",
            ),
        )
    }

    private fun deliverSample(sample: LiveLocationSample) {
        val now = System.currentTimeMillis()
        if (now - lastDeliveredTimeMs < MIN_DELIVER_INTERVAL_MS &&
            sample.provider.startsWith("baidu")
        ) {
            return
        }
        lastDeliveredTimeMs = now
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

    companion object {
        private const val TAG = "RealtimeLocation"
        private const val POLL_INTERVAL_MS = 500L
        private const val MIN_DELIVER_INTERVAL_MS = 400L
    }
}
