package com.powerchina.zhixun

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.powerchina.zhixun.dashcam.VideoKeyActivityHelper
import com.powerchina.zhixun.ui.ZhiXunNavHost
import com.powerchina.zhixun.util.ScreenOnHelper
import com.powerchina.zhixun.xiaozhi.XiaozhiAppEvents

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ScreenOnHelper.keepScreenOn(this)
        handleOpenXiaozhiIntent(intent)
        setContent {
            ZhiXunNavHost()
        }
    }

    override fun onNewIntent(intent: Intent) {
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
        XiaozhiAppEvents.requestOpenConversation(
            autoConnect = intent.getBooleanExtra(EXTRA_AUTO_CONNECT, false),
        )
    }

    companion object {
        const val EXTRA_OPEN_XIAOZHI = "extra_open_xiaozhi"
        const val EXTRA_AUTO_CONNECT = "extra_auto_connect"
    }
}
