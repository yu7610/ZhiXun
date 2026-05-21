package com.powerchina.zhixun.xiaozhi.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.powerchina.zhixun.MainActivity
import com.powerchina.zhixun.R

/**
 * 后台/息屏持续监听「你好」的前台服务。
 */
class XiaozhiWakeForegroundService : Service() {

    private var wakeListener: WakeListener? = null

    @Volatile
    private var pausedByConversation = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                pausedByConversation = true
                wakeListener?.pause()
                return START_STICKY
            }
            ACTION_RESUME -> {
                pausedByConversation = false
                if (canStartListening()) {
                    startDetector()
                }
                return START_STICKY
            }
            ACTION_STOP -> {
                stopDetector()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startInForeground()
        if (!pausedByConversation && canStartListening()) {
            startDetector()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wakeListener?.stop()
        wakeListener = null
        instance = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun canStartListening(): Boolean = hasMicPermission()

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureWakeListener(): WakeListener {
        wakeListener?.let { return it }
        val callback = { XiaozhiWakeCoordinator.onWakeDetected(applicationContext) }
        wakeListener = if (SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.i(TAG, "使用本地 SpeechRecognizer 唤醒")
            WakePhraseDetector(applicationContext, callback)
        } else {
            Log.i(TAG, "设备无 SpeechRecognizer，使用服务端 STT 唤醒")
            ServerWakeDetector(applicationContext, callback)
        }
        return wakeListener!!
    }

    private fun startDetector() {
        if (!canStartListening()) {
            Log.w(TAG, "无法启动唤醒监听：未授予麦克风权限")
            return
        }
        if (pausedByConversation) {
            Log.d(TAG, "对话占用中，跳过唤醒启动")
            return
        }
        val listener = ensureWakeListener()
        if (listener.isActive) {
            Log.d(TAG, "唤醒监听已在运行，跳过重复启动")
            return
        }
        listener.start()
    }

    private fun stopDetector() {
        wakeListener?.stop()
        wakeListener = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.wake_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.wake_notification_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.wake_notification_title))
            .setContentText(getString(R.string.wake_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "ZhiXunVoiceWake"
        private const val CHANNEL_ID = "xiaozhi_voice_wake"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_PAUSE = "com.powerchina.zhixun.wake.PAUSE"
        private const val ACTION_RESUME = "com.powerchina.zhixun.wake.RESUME"
        private const val ACTION_STOP = "com.powerchina.zhixun.wake.STOP"

        @Volatile
        private var instance: XiaozhiWakeForegroundService? = null

        fun isRunning(): Boolean = instance != null

        fun isWakeListeningActive(): Boolean = instance?.wakeListener?.isActive == true

        fun ensureStarted(context: Context) {
            val appContext = context.applicationContext
            if (!hasMicPermission(appContext)) {
                Log.d(TAG, "未授予麦克风权限，暂不启动唤醒服务")
                return
            }
            if (instance != null) return
            Log.i(TAG, "启动语音唤醒前台服务")
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, XiaozhiWakeForegroundService::class.java),
            )
        }

        fun ensureListeningActive(context: Context) {
            val appContext = context.applicationContext
            ensureStarted(appContext)
            instance?.let {
                it.pausedByConversation = false
                if (it.wakeListener?.isActive == true) {
                    Log.d(TAG, "唤醒监听已活跃")
                    return
                }
                Log.i(TAG, "确保唤醒监听活跃")
                it.startDetector()
            }
        }

        fun pauseListening(context: Context) {
            val appContext = context.applicationContext
            if (instance != null) {
                instance?.pausedByConversation = true
                instance?.wakeListener?.pause()
            } else {
                appContext.startService(
                    Intent(appContext, XiaozhiWakeForegroundService::class.java).apply {
                        action = ACTION_PAUSE
                    },
                )
            }
        }

        fun resumeListening(context: Context) {
            val appContext = context.applicationContext
            if (instance != null) {
                instance?.pausedByConversation = false
                if (instance?.canStartListening() == true) {
                    instance?.startDetector()
                }
            } else {
                ensureStarted(appContext)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            appContext.startService(
                Intent(appContext, XiaozhiWakeForegroundService::class.java).apply {
                    action = ACTION_STOP
                },
            )
        }

        private fun hasMicPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
