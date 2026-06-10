package com.powerchina.zhixun.dashcam

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.powerchina.zhixun.util.ScreenOnHelper
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeForegroundService

/** 执法拍摄 / 行车记录仪独立页面 */
class DashcamActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ScreenOnHelper.attach(this)
        XiaozhiWakeForegroundService.ensureStarted(this)
        setContent {
            DashcamScreen(onBack = { finish() })
        }
    }

    override fun onResume() {
        super.onResume()
        DashcamForeground.setActive(true)
    }

    override fun onPause() {
        DashcamForeground.setActive(false)
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            ScreenOnHelper.onUserInteraction(this)
        }
        if (VideoKeyActivityHelper.dispatchKeyEvent(this, event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        ScreenOnHelper.onUserInteraction(this)
    }

    override fun onDestroy() {
        ScreenOnHelper.detach(this)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (VideoKeyActivityHelper.dispatchKeyEvent(this, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (VideoKeyActivityHelper.dispatchKeyEvent(this, event)) return true
        return super.onKeyUp(keyCode, event)
    }
}
