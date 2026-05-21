package com.powerchina.zhixun.xiaozhi.wake

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.powerchina.zhixun.MainActivity
import com.powerchina.zhixun.data.ConfigManager
import com.powerchina.zhixun.xiaozhi.XiaozhiLog
import com.powerchina.zhixun.xiaozhi.XiaozhiSessionManager

/**
 * 唤醒后：亮屏、连接小智、打开聊天页。
 */
object XiaozhiWakeCoordinator {

    private const val MODULE = "WakeCoord"
    private const val DEBOUNCE_MS = 3000L

    private var lastWakeAtMs = 0L

    /** 唤醒已触发、等待 UI 接管对话，此期间不恢复后台唤醒监听 */
    @Volatile
    private var wakeHandoffInProgress = false

    fun isWakeHandoffInProgress(): Boolean = wakeHandoffInProgress

    fun clearWakeHandoff(reason: String) {
        if (!wakeHandoffInProgress) return
        wakeHandoffInProgress = false
        XiaozhiLog.d(MODULE, "唤醒交接完成: $reason")
    }

    fun onWakeDetected(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastWakeAtMs < DEBOUNCE_MS) {
            XiaozhiLog.d(MODULE, "忽略重复唤醒 (debounce ${now - lastWakeAtMs}ms)")
            return
        }
        lastWakeAtMs = now

        val appContext = context.applicationContext
        if (!isConfigReady(appContext)) {
            XiaozhiLog.w(MODULE, "小智未配置，忽略唤醒")
            XiaozhiWakeForegroundService.resumeListening(appContext)
            return
        }

        wakeHandoffInProgress = true
        XiaozhiLog.i(MODULE, "★ 语音唤醒触发：${WakePhraseMatcher.WAKE_PHRASE}")

        XiaozhiWakeForegroundService.pauseListening(appContext)
        wakeScreen(appContext)

        val app = appContext as android.app.Application
        XiaozhiSessionManager.getInstance(app).ensureConnected()

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
        XiaozhiLog.i(MODULE, "启动 MainActivity 打开对话页")
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
        XiaozhiLog.d(MODULE, "已请求亮屏")
    }
}
