package com.powerchina.zhixun.location

import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.baidu.location.BDAbstractLocationListener
import com.baidu.location.BDLocation
import com.baidu.location.LocationClient
import com.baidu.location.LocationClientOption
import com.powerchina.zhixun.R
import kotlin.math.abs

/**
 * 百度定位采集引擎。
 *
 * 官方文档支持单次定位（onceLocation）与连续定位（scanSpan≥1000）。
 * 本机实测：连续定位出首点后 isStarted 恒 false，后续 start()/requestLocation 无回调，
 * 只有重建 Client 或单次定位脉冲可靠。因此采用：
 * - 文档推荐参数：Hight_Accuracy + OpenGnss + bd09ll + LocationNotify
 * - onceLocation=true，每 [SCAN_INTERVAL_MS] start() 打一枪（单次定位通道）
 * - 息屏：FGS + enableLocInForeground；系统 GPS 补充卫星点
 *
 * @see https://lbsyun.baidu.com/index.php?title=android-locsdk/guide/get-location/latlng
 */
object BaiduLocationReporter {

    const val TAG = "LocationReport"
    const val SCAN_INTERVAL_MS = 3_000

    private const val START_DELAY_MS = 300L
    private const val PULSE_TIMEOUT_MS = 12_000L
    private const val RETRY_DELAY_MS = 2_000L
    private const val BAIDU_FG_CHANNEL_ID = "zhixun_location_baidu_fg"
    private const val BAIDU_FG_NOTIFICATION_ID = 1003

    @Volatile private var appContext: Application? = null
    @Volatile private var locationClient: LocationClient? = null
    @Volatile private var running = false
    @Volatile private var screenInteractive = true
    @Volatile private var screenReceiverRegistered = false
    @Volatile private var baiduForegroundEnabled = false
    @Volatile private var awaitingPulse = false
    @Volatile private var lastPulseAtMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(BDLocation) -> Unit>()
    private var consecutiveMisses = 0
    private var systemGpsLocator: SystemGpsLocator? = null

    private val firstPulse = Runnable {
        if (running) firePulse("首次")
    }

    private val retryPulse = Runnable {
        if (!running || awaitingPulse) return@Runnable
        firePulse("失败重试")
    }

    private val pulseLoop = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                tickPulse()
            } finally {
                if (running) {
                    mainHandler.postDelayed(this, SCAN_INTERVAL_MS.toLong())
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT,
                -> onScreenOn(intent.action.orEmpty())
            }
        }
    }

    private val locationListener = object : BDAbstractLocationListener() {
        override fun onReceiveLocation(location: BDLocation?) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                handleBaiduLocation(location)
            } else {
                mainHandler.post { handleBaiduLocation(location) }
            }
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
        val app = context.applicationContext as Application
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { start(app) }
            return
        }
        appContext = app
        screenInteractive = isScreenInteractive(app)
        if (!BaiduSdkInitializer.isReady()) {
            BaiduSdkInitializer.ensureInitialized(app)
        }
        registerScreenReceiver(app)
        LocationReportForegroundService.ensureStarted(app)

        if (locationClient == null) {
            createClient(app)
        }

        if (!running) {
            running = true
            awaitingPulse = false
            consecutiveMisses = 0
            LocationQualityFilter.reset()
            mainHandler.removeCallbacks(pulseLoop)
            mainHandler.removeCallbacks(firstPulse)
            mainHandler.removeCallbacks(retryPulse)
            startSystemGps(app)
            mainHandler.postDelayed(firstPulse, START_DELAY_MS)
            mainHandler.postDelayed(pulseLoop, SCAN_INTERVAL_MS.toLong())
            Log.i(
                TAG,
                "百度单次定位脉冲已启动 interval=${SCAN_INTERVAL_MS}ms " +
                    "onceLocation=true Hight_Accuracy screenOn=$screenInteractive",
            )
        }
    }

    fun addListener(listener: (BDLocation) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { addListener(listener) }
            return
        }
        listeners.add(listener)
    }

    fun removeListener(listener: (BDLocation) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { removeListener(listener) }
            return
        }
        listeners.remove(listener)
        if (listeners.isEmpty()) stop()
    }

    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stop() }
            return
        }
        running = false
        awaitingPulse = false
        mainHandler.removeCallbacks(pulseLoop)
        mainHandler.removeCallbacks(firstPulse)
        mainHandler.removeCallbacks(retryPulse)
        stopSystemGps()
        unregisterScreenReceiver()
        appContext?.let { LocationReportForegroundService.ensureStopped(it) }
        lastPulseAtMs = 0L
        consecutiveMisses = 0
        LocationQualityFilter.reset()
        destroyClient()
        listeners.clear()
        Log.i(TAG, "百度定位 stop()")
    }

    fun isValid(location: BDLocation): Boolean {
        val lat = location.latitude
        val lng = location.longitude
        if (!lat.isFinite() || !lng.isFinite()) return false
        if (lat == 0.0 && lng == 0.0) return false
        if (abs(lat) < 1e-4 || abs(lng) < 1e-4) return false
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return false
        return when (location.locType) {
            BDLocation.TypeCriteriaException,
            BDLocation.TypeNetWorkException,
            BDLocation.TypeServerError,
            LOC_TYPE_SERVER_PERMISSION,
            LOC_TYPE_AK_INVALID,
            LOC_TYPE_LOCATION_SERVICE_OFF,
            69, 70, 71,
            -> false
            else -> true
        }
    }

    private fun handleBaiduLocation(location: BDLocation?) {
        if (!running) return
        if (location == null) {
            Log.w(TAG, "onReceiveLocation location=null")
            onPulseNoFix("null")
            return
        }
        Log.i(
            TAG,
            "onReceiveLocation type=${location.locType} " +
                "lat=${location.latitude} lng=${location.longitude} " +
                "radius=${location.radius}m " +
                "isStarted=${locationClient?.isStarted} screenOn=$screenInteractive",
        )
        if (!isValid(location)) {
            logLocTypeHint(location.locType, location.locTypeDescription.orEmpty())
            onPulseNoFix("type=${location.locType}")
            return
        }
        val filtered = LocationQualityFilter.filter(location)
        if (filtered == null) {
            Log.w(TAG, "定位点被质量过滤丢弃，准备重试")
            awaitingPulse = false
            mainHandler.removeCallbacks(retryPulse)
            mainHandler.postDelayed(retryPulse, RETRY_DELAY_MS)
            return
        }
        awaitingPulse = false
        consecutiveMisses = 0
        mainHandler.removeCallbacks(retryPulse)
        Log.i(
            TAG,
            "定位成功(百度) type=${filtered.locType} radius=${filtered.radius}m " +
                "lat=${filtered.latitude} lng=${filtered.longitude}",
        )
        dispatch(filtered)
    }

    private fun handleSystemGps(location: BDLocation) {
        if (!running) return
        val filtered = LocationQualityFilter.filter(location) ?: return
        consecutiveMisses = 0
        Log.i(
            TAG,
            "定位成功(系统GPS) type=${filtered.locType} radius=${filtered.radius}m " +
                "lat=${filtered.latitude} lng=${filtered.longitude}",
        )
        dispatch(filtered)
    }

    private fun tickPulse() {
        appContext?.let { LocationReportForegroundService.ensureStarted(it) }
        val now = System.currentTimeMillis()
        if (awaitingPulse && now - lastPulseAtMs < PULSE_TIMEOUT_MS) {
            Log.i(TAG, "pulse: 等待本轮回调 elapsed=${now - lastPulseAtMs}ms")
            return
        }
        if (awaitingPulse) {
            Log.w(TAG, "pulse: 本轮超时")
            onPulseNoFix("timeout")
            return
        }
        firePulse("定时${SCAN_INTERVAL_MS}ms")
    }

    private fun firePulse(reason: String) {
        val app = appContext ?: return
        if (!running) return
        mainHandler.removeCallbacks(retryPulse)
        var client = locationClient
        if (client == null) {
            createClient(app)
            client = locationClient
            if (client == null) {
                onPulseNoFix("client=null")
                return
            }
        }
        lastPulseAtMs = System.currentTimeMillis()
        awaitingPulse = true
        runCatching {
            // 单次定位：每次 start 出一点后服务停；偶发未停干净用 restart
            if (client.isStarted) {
                client.restart()
                Log.i(TAG, "$reason restart() 打一枪 screenOn=$screenInteractive")
            } else {
                client.start()
                Log.i(TAG, "$reason start() 打一枪 screenOn=$screenInteractive")
            }
            enableBaiduForegroundLocate(client)
        }.onFailure {
            awaitingPulse = false
            Log.e(TAG, "$reason 启动失败", it)
            onPulseNoFix("start失败")
        }
    }

    private fun onPulseNoFix(reason: String) {
        awaitingPulse = false
        consecutiveMisses++
        Log.w(TAG, "本轮无可用定位 reason=$reason miss#$consecutiveMisses")
        if (!running) return
        if (consecutiveMisses >= 3) {
            mainHandler.removeCallbacks(retryPulse)
            recreateClient("连续无可用点")
            return
        }
        mainHandler.removeCallbacks(retryPulse)
        mainHandler.postDelayed(retryPulse, RETRY_DELAY_MS)
    }

    private fun recreateClient(reason: String) {
        Log.w(TAG, "重建 LocationClient: $reason")
        mainHandler.removeCallbacks(retryPulse)
        destroyClient()
        consecutiveMisses = 0
        awaitingPulse = false
        val app = appContext ?: return
        createClient(app)
        mainHandler.postDelayed({
            if (running) firePulse("重建后")
        }, START_DELAY_MS)
    }

    private fun onScreenOff() {
        if (!running) return
        screenInteractive = false
        appContext?.let { LocationReportForegroundService.ensureStarted(it) }
        Log.i(TAG, "息屏：前台服务保活，脉冲继续")
        if (!awaitingPulse) firePulse("息屏补点")
    }

    private fun onScreenOn(action: String) {
        if (!running) return
        val wasOff = !screenInteractive
        screenInteractive = true
        if (!wasOff && action != Intent.ACTION_USER_PRESENT) return
        if (awaitingPulse) {
            Log.i(TAG, "亮屏($action)：已在等待本轮，跳过")
            return
        }
        Log.i(TAG, "亮屏($action)：立即打一枪")
        firePulse("亮屏")
    }

    private fun createClient(app: Application) {
        val client = runCatching { LocationClient(app) }.getOrElse { e ->
            Log.e(TAG, "创建 LocationClient 失败", e)
            return
        }
        client.registerLocationListener(locationListener)
        client.setLocOption(buildOnceOption())
        locationClient = client
        Log.i(TAG, "LocationClient 已创建 onceLocation=true interval=${SCAN_INTERVAL_MS}ms")
    }

    private fun destroyClient() {
        val client = locationClient ?: return
        if (baiduForegroundEnabled) {
            runCatching { client.disableLocInForeground(true) }
            baiduForegroundEnabled = false
        }
        runCatching { client.unRegisterLocationListener(locationListener) }
        runCatching { if (client.isStarted) client.stop() }
        locationClient = null
    }

    /**
     * 单次定位参数（文档 onceLocation）+ 高精度 GNSS。
     * scanSpan&lt;1000 / onceLocation=true → 每次 start 返回一次结果。
     */
    private fun buildOnceOption(): LocationClientOption {
        return LocationClientOption().apply {
            setLocationMode(LocationClientOption.LocationMode.Hight_Accuracy)
            setCoorType("bd09ll")
            setOpenGnss(true)
            setLocationNotify(true)
            setIsNeedAltitude(true)
            setIsNeedAddress(false)
            setIsNeedLocationDescribe(false)
            setNeedDeviceDirect(false)
            setIgnoreKillProcess(true)
            SetIgnoreCacheException(false)
            setIsEnableBeidouMode(true)
            setWifiCacheTimeOut(5 * 60 * 1000)
            setEnableSimulateGnss(false)
            setOnceLocation(true)
            setScanSpan(0)
            setFirstLocType(LocationClientOption.FirstLocType.SPEED_IN_FIRST_LOC)
        }
    }

    private fun startSystemGps(app: Application) {
        if (systemGpsLocator != null) return
        systemGpsLocator = SystemGpsLocator(app) { bd ->
            mainHandler.post { handleSystemGps(bd) }
        }.also { it.start() }
    }

    private fun stopSystemGps() {
        systemGpsLocator?.stop()
        systemGpsLocator = null
    }

    private fun enableBaiduForegroundLocate(client: LocationClient) {
        val app = appContext ?: return
        if (baiduForegroundEnabled) return
        runCatching {
            ensureBaiduFgChannel(app)
            client.enableLocInForeground(BAIDU_FG_NOTIFICATION_ID, buildBaiduFgNotification(app))
            baiduForegroundEnabled = true
            Log.i(TAG, "已开启百度前台定位通知 id=$BAIDU_FG_NOTIFICATION_ID")
        }.onFailure {
            Log.w(TAG, "enableLocInForeground 失败", it)
        }
    }

    private fun ensureBaiduFgChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(BAIDU_FG_CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                BAIDU_FG_CHANNEL_ID,
                "百度定位",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
    }

    private fun buildBaiduFgNotification(context: Context): Notification {
        val pi = PendingIntent.getActivity(
            context,
            0,
            Intent(context, LocationActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, BAIDU_FG_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.location_track_title))
            .setContentText("正在获取位置")
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun registerScreenReceiver(app: Application) {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(screenReceiver, filter)
        }
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        val app = appContext ?: return
        runCatching { app.unregisterReceiver(screenReceiver) }
        screenReceiverRegistered = false
    }

    private fun isScreenInteractive(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isInteractive != false
    }

    private fun dispatch(location: BDLocation) {
        listeners.toList().forEach { listener ->
            runCatching { listener(location) }
        }
    }

    private fun logLocTypeHint(locType: Int, desc: String) {
        val hint = when (locType) {
            62 -> "无法获取有效定位依据，检查网络/Wi‑Fi"
            63 -> "网络异常"
            69 -> "系统定位开关未打开"
            70 -> "无定位权限"
            71 -> "定位开关未开且无权限"
            LOC_TYPE_SERVER_PERMISSION -> "服务端定位失败"
            LOC_TYPE_AK_INVALID -> "AK 不存在或非法"
            LOC_TYPE_LOCATION_SERVICE_OFF -> "定位服务未开启（控制台勾选定位）"
            else -> desc.ifBlank { "UnKnown" }
        }
        Log.w(TAG, "定位失败 type=$locType desc=$hint")
    }

    private const val LOC_TYPE_SERVER_PERMISSION = 167
    private const val LOC_TYPE_AK_INVALID = 505
    private const val LOC_TYPE_LOCATION_SERVICE_OFF = 506
}
