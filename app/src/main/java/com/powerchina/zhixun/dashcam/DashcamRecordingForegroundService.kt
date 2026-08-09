package com.powerchina.zhixun.dashcam

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
 * 执法仪录像前台服务：息屏时保持 CPU/相机策略，避免系统杀掉录制。
 */
class DashcamRecordingForegroundService : Service() {

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
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
                "执法仪录像",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "息屏时继续录像"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, DashcamActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.dashcam_title))
            .setContentText(getString(R.string.dashcam_recording_fg_text))
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
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ZhiXun:DashcamRecording",
        ).apply {
            setReferenceCounted(false)
            acquire(8 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "DashcamRecFg"
        private const val CHANNEL_ID = "zhixun_dashcam_recording"
        private const val NOTIFICATION_ID = 1003
        private const val ACTION_STOP = "com.powerchina.zhixun.dashcam.STOP_RECORDING_FG"

        @Volatile
        private var instance: DashcamRecordingForegroundService? = null

        fun ensureStarted(context: Context) {
            if (instance != null) return
            val app = context.applicationContext
            val intent = Intent(app, DashcamRecordingForegroundService::class.java)
            runCatching {
                ContextCompat.startForegroundService(app, intent)
                Log.i(TAG, "执法仪录像前台服务已启动（息屏可继续录制）")
            }.onFailure {
                Log.e(TAG, "启动录像前台服务失败", it)
            }
        }

        fun ensureStopped(context: Context) {
            val app = context.applicationContext
            runCatching {
                app.startService(
                    Intent(app, DashcamRecordingForegroundService::class.java).apply {
                        action = ACTION_STOP
                    },
                )
                Log.i(TAG, "执法仪录像前台服务已停止")
            }
        }
    }
}
