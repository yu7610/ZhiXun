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
 * 百度定位脉冲模式（适配本机 SDK 行为）：
 *
 * 设备上连续定位（scanSpan>0）首点后服务不起（isStarted 恒 false、requestLocation=1），
 * 首点实际来自单次定位通道。因此改为：
 * - onceLocation=true，每 [SCAN_INTERVAL_MS] 主动 start() 打一枪
 * - 仅分发本次新回调，绝不复用旧经纬度
 * - 息屏靠 [LocationReportForegroundService] + 百度 enableLocInForeground
 *
 * @see https://lbsyun.baidu.com/index.php?title=android-locsdk/guide/get-location/latlng
 */
object BaiduLocationReporter {

    const val TAG = "LocationReport"
    const val SCAN_INTERVAL_MS = 6_000
    private const val START_DELAY_MS = 200L
    /** 单次定位超时，超时才允许下一发 */
    private const val PULSE_TIMEOUT_MS = 12_000L
    private const val BAIDU_FG_CHANNEL_ID = "zhixun_location_baidu_fg"
    private const val BAIDU_FG_NOTIFICATION_ID = 1003

    @Volatile
    private var appContext: Application? = null
    @Volatile
    private var locationClient: LocationClient? = null
    @Volatile
    private var running = false
    @Volatile
    private var screenInteractive = true
    @Volatile
    private var screenReceiverRegistered = false
    @Volatile
    private var baiduForegroundEnabled = false
    /** 已发出 start，等待本次新点 */
    @Volatile
    private var awaitingPulse = false
    @Volatile
    private var lastPulseAtMs: Long = 0L
    @Volatile
    private var lastCallbackAtMs: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(BDLocation) -> Unit>()
    private var consecutiveMisses = 0

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

    private val firstPulse = Runnable {
        if (!running) return@Runnable
        firePulse("首次")
    }

    private val locationListener = object : BDAbstractLocationListener() {
        override fun onReceiveLocation(location: BDLocation?) {
            if (location == null) {
                Log.w(TAG, "onReceiveLocation location=null")
                return
            }
            Log.i(
                TAG,
                "onReceiveLocation type=${location.locType} " +
                    "lat=${location.latitude} lng=${location.longitude} " +
                    "isStarted=${locationClient?.isStarted} screenOn=$screenInteractive",
            )
            if (!isValid(location)) {
                Log.w(
                    TAG,
                    "定位失败 type=${location.locType} desc=${location.locTypeDescription.orEmpty()}",
                )
                return
            }
            awaitingPulse = false
            consecutiveMisses = 0
            lastCallbackAtMs = System.currentTimeMillis()
            Log.i(
                TAG,
                "定位成功(新点) type=${location.locType} " +
                    "lat=${location.latitude} lng=${location.longitude}",
            )
            dispatch(location)
            // 单次定位后服务会停；下一发由 pulseLoop 触发
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
            mainHandler.removeCallbacks(pulseLoop)
            mainHandler.removeCallbacks(firstPulse)
            mainHandler.postDelayed(firstPulse, START_DELAY_MS)
            mainHandler.postDelayed(pulseLoop, SCAN_INTERVAL_MS.toLong())
            Log.i(
                TAG,
                "定位脉冲已启动 interval=${SCAN_INTERVAL_MS}ms onceLocation=true " +
                    "screenOn=$screenInteractive",
            )
        }
    }

    fun addListener(listener: (BDLocation) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (BDLocation) -> Unit) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            stop()
        }
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
        unregisterScreenReceiver()
        appContext?.let { LocationReportForegroundService.ensureStopped(it) }
        lastPulseAtMs = 0L
        lastCallbackAtMs = 0L
        consecutiveMisses = 0
        destroyClient()
        listeners.clear()
        Log.i(TAG, "定位脉冲 stop()")
    }

    fun requestLocate(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { requestLocate(reason) }
            return
        }
        if (!running) {
            appContext?.let { start(it) } ?: return
        }
        firePulse(reason)
    }

    private fun tickPulse() {
        appContext?.let { LocationReportForegroundService.ensureStarted(it) }
        val now = System.currentTimeMillis()
        if (awaitingPulse && now - lastPulseAtMs < PULSE_TIMEOUT_MS) {
            Log.i(
                TAG,
                "pulse: 等待本轮回调 elapsed=${now - lastPulseAtMs}ms screenOn=$screenInteractive",
            )
            return
        }
        if (awaitingPulse) {
            consecutiveMisses++
            Log.w(TAG, "pulse: 本轮超时无新点 miss#$consecutiveMisses，准备下一发")
            awaitingPulse = false
            if (consecutiveMisses >= 3) {
                recreateClient("连续无新点")
                return
            }
        }
        firePulse("定时${SCAN_INTERVAL_MS}ms")
    }

    private fun firePulse(reason: String) {
        val app = appContext ?: return
        var client = locationClient
        if (client == null) {
            createClient(app)
            client = locationClient ?: return
        }
        // 官方：先 start，再 enableLocInForeground
        lastPulseAtMs = System.currentTimeMillis()
        awaitingPulse = true
        runCatching {
            if (client.isStarted) {
                // 单次模式偶发未停干净：restart = stop + 延迟 start
                client.restart()
                Log.i(TAG, "$reason restart() 打一枪要新点 screenOn=$screenInteractive")
            } else {
                client.start()
                Log.i(TAG, "$reason start() 打一枪要新点 screenOn=$screenInteractive")
            }
            enableBaiduForegroundLocate(client)
        }.onFailure {
            awaitingPulse = false
            Log.e(TAG, "$reason 启动定位失败", it)
            consecutiveMisses++
            if (consecutiveMisses >= 2) {
                recreateClient("脉冲失败")
            }
        }
    }

    private fun onScreenOff() {
        if (!running) return
        screenInteractive = false
        appContext?.let { LocationReportForegroundService.ensureStarted(it) }
        Log.i(TAG, "息屏：前台服务保活，下一发脉冲仍取新点")
        // 若当前未在等回调，立即补一发新点
        if (!awaitingPulse) {
            firePulse("息屏补点")
        }
    }

    private fun onScreenOn(action: String) {
        if (!running) return
        val wasOff = !screenInteractive
        screenInteractive = true
        if (!wasOff && action != Intent.ACTION_USER_PRESENT) return
        Log.i(TAG, "亮屏($action)：立即打一枪要新点")
        firePulse("亮屏")
    }

    private fun recreateClient(reason: String) {
        Log.w(TAG, "重建 LocationClient: $reason")
        destroyClient()
        consecutiveMisses = 0
        awaitingPulse = false
        val app = appContext ?: return
        createClient(app)
        mainHandler.postDelayed({ if (running) firePulse("重建后") }, START_DELAY_MS)
    }

    private fun createClient(app: Application) {
        val client = runCatching { LocationClient(app) }.getOrElse { e ->
            Log.e(TAG, "创建 LocationClient 失败", e)
            return
        }
        client.registerLocationListener(locationListener)
        client.setLocOption(buildOnceOption())
        locationClient = client
        Log.i(TAG, "LocationClient 已创建 onceLocation=true scanSpan=0（脉冲模式）")
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

    /** 单次定位：每次 start 出一新点 */
    private fun buildOnceOption(): LocationClientOption {
        return LocationClientOption().apply {
            setLocationMode(LocationClientOption.LocationMode.Hight_Accuracy)
            setCoorType("bd09ll")
            setOpenGnss(true)
            setIsNeedAddress(false)
            setIsNeedLocationDescribe(false)
            setNeedDeviceDirect(false)
            setIgnoreKillProcess(true)
            SetIgnoreCacheException(false)
            setIsNeedAltitude(true)
            setLocationNotify(false)
            setIsEnableBeidouMode(true)
            setWifiCacheTimeOut(5 * 60 * 1000)
            setFirstLocType(LocationClientOption.FirstLocType.SPEED_IN_FIRST_LOC)
            setOnceLocation(true)
            setScanSpan(0)
        }
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
            .setContentText("正在获取最新位置")
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
            LOC_TYPE_LOCATION_SWITCH_OFF,
            -> false
            else -> true
        }
    }

    private const val LOC_TYPE_SERVER_PERMISSION = 167
    private const val LOC_TYPE_LOCATION_SWITCH_OFF = 505
}
