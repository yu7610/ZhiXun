package com.powerchina.zhixun.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.powerchina.zhixun.R

/**
 * 定位页前台服务：保证息屏时仍可获取**新**定位并上报（系统要求 location 类型 FGS）。
 */
class LocationReportForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        instance = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "定位上报",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "息屏时持续获取最新位置并上报"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, LocationActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.location_track_title))
            .setContentText("正在获取并上报最新位置")
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZhiXun:LocationReport").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L) // 最长 1 小时，离开定位页会 release
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "LocationReport"
        private const val CHANNEL_ID = "zhixun_location_report"
        const val NOTIFICATION_ID = 1002
        private const val ACTION_STOP = "com.powerchina.zhixun.location.STOP_REPORT_FG"

        @Volatile
        private var instance: LocationReportForegroundService? = null

        fun ensureStarted(context: Context) {
            if (instance != null) return
            val app = context.applicationContext
            val intent = Intent(app, LocationReportForegroundService::class.java)
            runCatching {
                ContextCompat.startForegroundService(app, intent)
                Log.i(TAG, "定位前台服务已启动（息屏可继续定位）")
            }.onFailure {
                Log.e(TAG, "启动定位前台服务失败", it)
            }
        }

        fun ensureStopped(context: Context) {
            val app = context.applicationContext
            runCatching {
                app.startService(
                    Intent(app, LocationReportForegroundService::class.java).apply {
                        action = ACTION_STOP
                    },
                )
                Log.i(TAG, "定位前台服务已停止")
            }
        }
    }
}
