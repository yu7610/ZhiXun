package com.powerchina.zhixun.xiaozhi.wake

import android.util.Log

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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.powerchina.zhixun.MainActivity
import com.powerchina.zhixun.R
import com.powerchina.zhixun.data.ConfigManager
import com.powerchina.zhixun.xiaozhi.XiaozhiAppEvents
/**
 * 后台/息屏持续监听「你好，智询」的前台服务。
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
                Log.d(TAG, "onStartCommand PAUSE")
                wakeListener?.pause()
                return START_STICKY
            }
            ACTION_RESUME -> {
                pausedByConversation = false
                Log.d(TAG, "onStartCommand RESUME")
                if (canStartListening()) {
                    startDetector()
                }
                return START_STICKY
            }
            ACTION_STOP -> {
                Log.i(TAG, "onStartCommand STOP")
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
        val cfg = ConfigManager(applicationContext).loadConfig()
        val preferServer = cfg.websocketUrl.isNotBlank() || cfg.otaUrl.isNotBlank()
        wakeListener = when {
            preferServer -> {
                Log.i(TAG, "引擎=ServerSTT (小智已配置，与按键对话同路径)")
                ServerWakeDetector(applicationContext, callback)
            }
            SpeechRecognizer.isRecognitionAvailable(this) -> {
                Log.i(TAG, "引擎=SpeechRecognizer")
                WakePhraseDetector(applicationContext, callback)
            }
            else -> {
                Log.i(TAG, "引擎=ServerSTT (无系统 SR)")
                ServerWakeDetector(applicationContext, callback)
            }
        }
        return wakeListener!!
    }

    private fun startDetector() {
        if (!canStartListening()) {
            Log.w(TAG, "无麦克风权限，无法启动")
            return
        }
        if (pausedByConversation) {
            Log.d(TAG, "对话占用中，跳过")
            return
        }
        if (XiaozhiWakeCoordinator.isWakeHandoffInProgress()) {
            Log.d(TAG, "唤醒交接中，跳过")
            return
        }
        val listener = ensureWakeListener()
        if (listener.isActive && (listener.isStreaming || listener.isStarting)) {
            Log.d(TAG, "已在运行，跳过")
            return
        }
        Log.i(TAG, "启动唤醒监听")
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
        private const val TAG = "WakeService"
        private const val CHANNEL_ID = "xiaozhi_voice_wake"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_PAUSE = "com.powerchina.zhixun.wake.PAUSE"
        private const val ACTION_RESUME = "com.powerchina.zhixun.wake.RESUME"
        private const val ACTION_STOP = "com.powerchina.zhixun.wake.STOP"

        @Volatile
        private var instance: XiaozhiWakeForegroundService? = null

        @Volatile
        private var micClaimedByConversation = false

        fun isRunning(): Boolean = instance != null

        fun isWakeListeningActive(): Boolean = instance?.wakeListener?.isActive == true

        fun isWakeListeningHealthy(): Boolean {
            val service = instance ?: return false
            if (micClaimedByConversation || service.pausedByConversation) return false
            val listener = service.wakeListener ?: return false
            return listener.isStreaming || listener.isStarting
        }

        fun isConversationMicClaimed(): Boolean = micClaimedByConversation

        fun ensureStarted(context: Context) {
            val appContext = context.applicationContext
            if (instance != null) {
                instance?.let { service ->
                    if (micClaimedByConversation || service.pausedByConversation) return@let
                    if (!service.canStartListening()) return@let
                    val listener = service.wakeListener
                    if (listener?.isActive == true && (listener.isStreaming || listener.isStarting)) {
                        return@let
                    }
                    Log.d(TAG, "ensureStarted: 服务已存在，补启动监听")
                    service.startDetector()
                }
                return
            }
            if (!hasMicPermission(appContext)) {
                Log.d(TAG, "无麦克风权限，暂不启动服务")
                return
            }
            Log.i(TAG, "启动前台服务")
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, XiaozhiWakeForegroundService::class.java),
            )
        }

        fun ensureListeningActive(context: Context) {
            val appContext = context.applicationContext
            if (micClaimedByConversation) {
                Log.d(TAG, "对话占用麦克风，不恢复唤醒监听")
                return
            }
            if (XiaozhiAppEvents.isPhotoSessionActive()) {
                Log.d(TAG, "拍照会话中，不恢复监听")
                return
            }
            if (XiaozhiWakeCoordinator.isWakeHandoffInProgress()) {
                Log.d(TAG, "唤醒交接中，不恢复监听")
                return
            }
            ensureStarted(appContext)
            instance?.let { service ->
                service.pausedByConversation = false
                val listener = service.wakeListener
                when {
                    listener?.isStreaming == true -> {
                        Log.d(TAG, "监听已活跃且正在推流")
                        return
                    }
                    listener?.isStarting == true -> {
                        Log.d(TAG, "监听启动中，不重启")
                        return
                    }
                    listener?.isActive == true -> {
                        Log.w(TAG, "监听假活跃（未推流），重启")
                    }
                }
                service.wakeListener?.stop()
                service.wakeListener = null
                Log.i(TAG, "ensureListeningActive 重启监听")
                service.startDetector()
            }
        }

        fun releaseMicrophoneForConversation(context: Context) {
            val appContext = context.applicationContext
            Log.d(TAG, "releaseMicrophoneForConversation")
            instance?.let { service ->
                service.pausedByConversation = true
                service.wakeListener?.stop()
                service.wakeListener = null
            }
            appContext.startService(
                Intent(appContext, XiaozhiWakeForegroundService::class.java).apply {
                    action = ACTION_PAUSE
                },
            )
        }

        /** 对话开麦前声明占用，防止待机逻辑把麦克风抢回唤醒服务 */
        fun claimMicrophoneForConversation(context: Context) {
            micClaimedByConversation = true
            releaseMicrophoneForConversation(context)
        }

        fun releaseConversationMicrophoneClaim(context: Context) {
            if (!micClaimedByConversation) return
            micClaimedByConversation = false
            Log.d(TAG, "releaseConversationMicrophoneClaim")
        }

        fun pauseListening(context: Context) {
            val appContext = context.applicationContext
            Log.d(TAG, "pauseListening")
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
            if (XiaozhiWakeCoordinator.isWakeHandoffInProgress()) {
                Log.d(TAG, "唤醒交接中，不 resume")
                return
            }
            Log.d(TAG, "resumeListening")
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
