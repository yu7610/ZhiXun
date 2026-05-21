package com.powerchina.zhixun

import android.util.Log

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.powerchina.zhixun.dashcam.VideoKeyActivityHelper
import com.powerchina.zhixun.ui.ZhiXunNavHost
import com.powerchina.zhixun.util.ScreenOnHelper
import com.powerchina.zhixun.xiaozhi.XiaozhiAppEvents
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeForegroundService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyVoiceWakeWindowFlags(intent)
        enableEdgeToEdge()
        ScreenOnHelper.keepScreenOn(this)
        handleOpenXiaozhiIntent(intent)
        XiaozhiWakeForegroundService.ensureStarted(this)
        setContent {
            ZhiXunNavHost()
        }
    }

    override fun onNewIntent(intent: Intent) {
        applyVoiceWakeWindowFlags(intent)
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenXiaozhiIntent(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (VideoKeyActivityHelper.dispatchKeyEvent(this, event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun handleOpenXiaozhiIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_XIAOZHI, false) != true) return
        val wake = intent.getBooleanExtra(EXTRA_WAKE_FROM_VOICE, false)
        val autoConnect = intent.getBooleanExtra(EXTRA_AUTO_CONNECT, false)
        Log.i(TAG, "handleOpenXiaozhiIntent wake=$wake autoConnect=$autoConnect")
        XiaozhiAppEvents.requestOpenConversation(
            autoConnect = autoConnect,
            fromVoiceWake = wake,
        )
    }

    private fun applyVoiceWakeWindowFlags(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_WAKE_FROM_VOICE, false) != true) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    companion object {
        private const val TAG = "MainActivity"

        const val EXTRA_OPEN_XIAOZHI = "extra_open_xiaozhi"
        const val EXTRA_AUTO_CONNECT = "extra_auto_connect"
        const val EXTRA_WAKE_FROM_VOICE = "extra_wake_from_voice"
    }
}
