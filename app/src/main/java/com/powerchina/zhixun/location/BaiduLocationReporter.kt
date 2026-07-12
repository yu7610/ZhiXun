package com.powerchina.zhixun.location

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.baidu.location.BDAbstractLocationListener
import com.baidu.location.BDLocation
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption
import com.powerchina.zhixun.R
import kotlin.math.abs

/**
 * 百度定位：进入定位页后每 60 秒定位一次。
 */
object BaiduLocationReporter {

    const val TAG = "LocationReport"
    const val SCAN_INTERVAL_MS = 60_000
    private const val LOCATION_TIMEOUT_MS = 15_000L
    private const val INVALID_RETRY_MS = 10_000L
    private const val CALLBACK_DEDUP_MS = 5_000L
    private const val LOCATION_NOTIFICATION_ID = 2002
    private const val LOCATION_CHANNEL_ID = "location_tracking"

    @Volatile
    private var client: LocationClient? = null
    @Volatile
    private var lastValidLocation: BDLocation? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(BDLocation) -> Unit>()
    private var lastDeliveredKey: String? = null
    private var lastDeliveredAtMs: Long = 0L
    private var periodicScheduled = false
    private val periodicLocate = Runnable { triggerLocate("定时") }
    private val invalidRetry = Runnable { triggerLocate("无效重试") }
    private val locationTimeout = Runnable {
        Log.w(TAG, "定位超时无回调，重新定位")
        triggerLocate("超时重试")
    }
    private val baiduListener = object : BDAbstractLocationListener() {
        override fun onReceiveLocation(location: BDLocation?) {
            mainHandler.removeCallbacks(locationTimeout)
            if (location == null) {
                Log.w(TAG, "百度定位回调 location=null")
                scheduleInvalidRetry()
                return
            }
            Log.i(
                TAG,
                "百度定位原始回调 type=${location.locType} lat=${location.latitude} lng=${location.longitude}",
            )
            if (!isValid(location)) {
                Log.w(
                    TAG,
                    "百度定位无效 type=${location.locType} lat=${location.latitude} lng=${location.longitude}",
                )
                scheduleInvalidRetry()
                return
            }
            if (isDuplicateCallback(location)) {
                Log.i(TAG, "跳过重复定位回调 lat=${location.latitude} lng=${location.longitude}")
                return
            }
            lastValidLocation = location
            Log.i(
                TAG,
                "百度定位回调 type=${location.locType} lat=${location.latitude} lng=${location.longitude} " +
                    "alt=${location.altitude} hasAlt=${location.hasAltitude()}",
            )
            listeners.toList().forEach { listener ->
                runCatching { listener(location) }
            }
            scheduleNextLocate()
        }

        override fun onLocDiagnosticMessage(
            locType: Int,
            diagnosticType: Int,
            diagnosticMessage: String?,
        ) {
            Log.w(
                TAG,
                "定位诊断 locType=$locType diagnosticType=$diagnosticType msg=$diagnosticMessage",
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        val app = context.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { start(app) }
            return
        }
        if (client != null) return
        val application = app as Application
        if (!BaiduSdkInitializer.isReady()) {
            BaiduSdkInitializer.ensureInitialized(application)
        }
        val locationClient = LocationClient(application)
        val option = buildLocationOption()
        locationClient.locOption = option
        locationClient.registerLocationListener(baiduListener)
        locationClient.start()
        runCatching {
            locationClient.enableLocInForeground(
                LOCATION_NOTIFICATION_ID,
                buildForegroundNotification(application),
            )
        }.onFailure { e ->
            Log.w(TAG, "enableLocInForeground 失败，继续定位", e)
        }
        client = locationClient
        lastDeliveredKey = null
        lastDeliveredAtMs = 0L
        periodicScheduled = false
        Log.i(TAG, "百度定位已启动 interval=${SCAN_INTERVAL_MS}ms")
        requestLocate("启动", restartClient = false)
    }

    fun addListener(listener: (BDLocation) -> Unit) {
        listeners.add(listener)
        lastValidLocation?.let { cached ->
            Log.i(TAG, "向新监听器回放最近定位 lat=${cached.latitude} lng=${cached.longitude}")
            runCatching { listener(cached) }
        }
    }

    fun removeListener(listener: (BDLocation) -> Unit) {
        listeners.remove(listener)
        stopIfIdle()
    }

    fun stop() {
        listeners.clear()
        stopClient()
    }

    private fun stopIfIdle() {
        if (listeners.isEmpty()) {
            stopClient()
        }
    }

    private fun stopClient() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stopClient() }
            return
        }
        mainHandler.removeCallbacks(periodicLocate)
        mainHandler.removeCallbacks(locationTimeout)
        mainHandler.removeCallbacks(invalidRetry)
        periodicScheduled = false
        lastDeliveredKey = null
        lastDeliveredAtMs = 0L
        lastValidLocation = null
        runCatching { client?.disableLocInForeground(true) }
        runCatching { client?.unRegisterLocationListener(baiduListener) }
        runCatching { client?.stop() }
        client = null
        Log.i(TAG, "百度定位已停止")
    }

    private fun buildLocationOption(): LocationClientOption {
        return LocationClientOption().apply {
            setLocationMode(LocationClientOption.LocationMode.Hight_Accuracy)
            setCoorType("bd09ll")
            setScanSpan(0)
            setOpenGnss(true)
            setIsNeedAltitude(true)
            setIsNeedLocationDescribe(false)
            setIgnoreKillProcess(true)
            setOnceLocation(true)
            setLocationNotify(false)
            setIsEnableBeidouMode(true)
            setFirstLocType(LocationClientOption.FirstLocType.ACCURACY_IN_FIRST_LOC)
        }
    }

    fun requestLocate(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { requestLocate(reason) }
            return
        }
        triggerLocate(reason)
    }

    private fun requestLocate(reason: String, restartClient: Boolean) {
        val current = client ?: return
        periodicScheduled = false
        mainHandler.removeCallbacks(locationTimeout)
        Log.i(TAG, "$reason 触发定位 started=${current.isStarted} restart=$restartClient")
        if (restartClient) {
            current.restart()
        }
        var code = current.requestLocation()
        Log.i(TAG, "$reason requestLocation 返回=$code started=${current.isStarted}")
        if (code == 1) {
            current.start()
            code = current.requestLocation()
            Log.i(TAG, "$reason start 后 requestLocation 返回=$code started=${current.isStarted}")
        }
        mainHandler.postDelayed(locationTimeout, LOCATION_TIMEOUT_MS)
    }

    private fun triggerLocate(reason: String) {
        val restartClient = reason !in NO_RESTART_REASONS
        requestLocate(reason, restartClient)
    }

    private val NO_RESTART_REASONS = setOf("启动", "定时", "进入定位页")

    private fun scheduleInvalidRetry() {
        mainHandler.removeCallbacks(locationTimeout)
        mainHandler.removeCallbacks(invalidRetry)
        mainHandler.postDelayed(invalidRetry, INVALID_RETRY_MS)
    }

    private fun scheduleNextLocate() {
        if (periodicScheduled) return
        periodicScheduled = true
        mainHandler.removeCallbacks(periodicLocate)
        mainHandler.postDelayed(periodicLocate, SCAN_INTERVAL_MS.toLong())
    }

    private fun isDuplicateCallback(location: BDLocation): Boolean {
        val key = "%.5f,%.5f".format(location.latitude, location.longitude)
        val now = System.currentTimeMillis()
        val duplicate = key == lastDeliveredKey && now - lastDeliveredAtMs < CALLBACK_DEDUP_MS
        if (!duplicate) {
            lastDeliveredKey = key
            lastDeliveredAtMs = now
        }
        return duplicate
    }

    fun isValid(location: BDLocation): Boolean {
        val lat = location.latitude
        val lng = location.longitude
        if (!lat.isFinite() || !lng.isFinite()) return false
        if (lat == 0.0 && lng == 0.0) return false
        if (abs(lat) < 1e-4 || abs(lng) < 1e-4) return false
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return false
        return when (location.locType) {
            LOC_TYPE_NETWORK_EXCEPTION,
            LOC_TYPE_SERVER_ERROR,
            LOC_TYPE_LOCATION_SWITCH_OFF,
            -> false
            else -> true
        }
    }

    private const val LOC_TYPE_NETWORK_EXCEPTION = 63
    private const val LOC_TYPE_SERVER_ERROR = 67
    private const val LOC_TYPE_LOCATION_SWITCH_OFF = 505

    private fun buildForegroundNotification(context: Context): android.app.Notification {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                LOCATION_CHANNEL_ID,
                "定位服务",
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, LOCATION_CHANNEL_ID)
            .setContentTitle("定位中")
            .setContentText("正在连续定位并上报位置")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
