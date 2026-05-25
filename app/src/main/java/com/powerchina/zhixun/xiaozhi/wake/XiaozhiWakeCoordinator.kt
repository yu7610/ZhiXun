package com.powerchina.zhixun.xiaozhi.wake

import android.util.Log

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.powerchina.zhixun.MainActivity
import com.powerchina.zhixun.data.ConfigManager
import com.powerchina.zhixun.physicalkey.PhysicalKeyInterceptor
import com.powerchina.zhixun.xiaozhi.XiaozhiAppEvents
import com.powerchina.zhixun.xiaozhi.VoiceFlowLog
import com.powerchina.zhixun.xiaozhi.XiaozhiSessionManager

/**
 * 唤醒后：亮屏、连接小智、打开聊天页。
 */
object XiaozhiWakeCoordinator {

    private const val TAG = "WakeCoord"
    private const val DEBOUNCE_MS = 3000L
    private const val HANDOFF_TIMEOUT_MS = 25_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastWakeAtMs = 0L

    /** 唤醒已触发、等待 UI 接管对话，此期间不恢复后台唤醒监听 */
    @Volatile
    private var wakeHandoffInProgress = false

    /**
     * WakeSTT 上行已触发服务端问候 TTS（tts start 常早于 STT 命中）。
     * 交接期间勿 sendStopListening/abort，否则服务端只回文字不发 Opus。
     */
    @Volatile
    private var serverGreetingTtsPending = false

    private val handoffTimeoutRunnable = Runnable {
        if (!wakeHandoffInProgress) return@Runnable
        val ctx = appContextForHandoff ?: return@Runnable
        if (XiaozhiWakeForegroundService.isConversationMicClaimed()) {
            Log.w(TAG, "唤醒交接超时但对话仍占用麦克风，延长等待")
            scheduleHandoffTimeout(ctx)
            return@Runnable
        }
        wakeHandoffInProgress = false
        Log.w(TAG, "唤醒交接超时，恢复后台监听")
        XiaozhiWakeForegroundService.resumeListening(ctx)
    }

    @Volatile
    private var appContextForHandoff: Context? = null

    fun isWakeHandoffInProgress(): Boolean = wakeHandoffInProgress

    fun onServerGreetingTtsStart() {
        serverGreetingTtsPending = true
        VoiceFlowLog.step("wakeCoord.greetingTts", "WakeSTT 路径 server tts start")
    }

    fun hasServerGreetingTtsPending(): Boolean = serverGreetingTtsPending

    fun clearServerGreetingTtsPending() {
        serverGreetingTtsPending = false
    }

    /** 问候 TTS 进行中：暂停唤醒推流，但不向服务端发 listen/stop */
    fun shouldDeferWakeStopListening(): Boolean =
        wakeHandoffInProgress || serverGreetingTtsPending

    fun refreshHandoffTimeout(context: Context) {
        if (!wakeHandoffInProgress) return
        scheduleHandoffTimeout(context.applicationContext)
    }

    fun clearWakeHandoff(reason: String) {
        if (!wakeHandoffInProgress) return
        wakeHandoffInProgress = false
        serverGreetingTtsPending = false
        mainHandler.removeCallbacks(handoffTimeoutRunnable)
        Log.d(TAG, "唤醒交接完成: $reason")
    }

    private fun scheduleHandoffTimeout(context: Context) {
        appContextForHandoff = context.applicationContext
        mainHandler.removeCallbacks(handoffTimeoutRunnable)
        mainHandler.postDelayed(handoffTimeoutRunnable, HANDOFF_TIMEOUT_MS)
    }

    fun onWakeDetected(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastWakeAtMs < DEBOUNCE_MS) {
            Log.d(TAG, "忽略重复唤醒 (debounce ${now - lastWakeAtMs}ms)")
            return
        }
        lastWakeAtMs = now

        val appContext = context.applicationContext
        if (!isConfigReady(appContext)) {
            Log.w(TAG, "小智未配置，忽略唤醒")
            XiaozhiWakeForegroundService.resumeListening(appContext)
            return
        }

        wakeHandoffInProgress = true
        scheduleHandoffTimeout(appContext)
        Log.i(TAG, "★ 语音唤醒触发：${WakePhraseMatcher.WAKE_PHRASE}")

        XiaozhiWakeForegroundService.pauseListening(appContext)
        wakeScreen(appContext)

        val app = appContext as android.app.Application
        XiaozhiSessionManager.getInstance(app).ensureConnected()

        if (PhysicalKeyInterceptor.isAppInForeground) {
            Log.i(TAG, "应用在前台，通过 AppEvents 打开对话")
            XiaozhiAppEvents.requestOpenConversation(
                autoConnect = true,
                fromVoiceWake = true,
            )
            return
        }

        val launchIntent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
            putExtra(MainActivity.EXTRA_OPEN_XIAOZHI, true)
            putExtra(MainActivity.EXTRA_AUTO_CONNECT, true)
            putExtra(MainActivity.EXTRA_WAKE_FROM_VOICE, true)
        }
        Log.i(TAG, "启动 MainActivity 打开对话页")
        appContext.startActivity(launchIntent)
    }

    private fun isConfigReady(context: Context): Boolean {
        val cfg = ConfigManager(context).loadConfig()
        val hasEndpoint = cfg.otaUrl.isNotBlank() || cfg.websocketUrl.isNotBlank()
        return hasEndpoint && cfg.macAddress.isNotBlank() && cfg.token.isNotBlank()
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "ZhiXun:VoiceWake",
        )
        wakeLock.acquire(10_000L)
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
        Log.d(TAG, "已请求亮屏")
    }
}
