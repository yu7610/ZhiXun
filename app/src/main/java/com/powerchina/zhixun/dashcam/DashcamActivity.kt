package com.powerchina.zhixun.dashcam

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.powerchina.zhixun.util.ScreenOnHelper
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeForegroundService

/** 执法拍摄 / 行车记录仪独立页面 */
class DashcamActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DashcamActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        McpCameraHolder.pauseForDashcam()
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
        McpCameraHolder.pauseForDashcam()
    }

    override fun onPause() {
        super.onPause()
        // 息屏也会走 onPause：录像中不标记后台，避免停录/解绑相机
        if (isFinishing) {
            Log.i(TAG, "onPause finishing → 释放执法仪前台标记")
            DashcamForeground.setActive(false)
        } else {
            Log.i(TAG, "onPause（可能息屏）→ 保持执法仪会话，录像继续")
        }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            DashcamForeground.setActive(false)
        }
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
        Log.i(TAG, "onDestroy → 释放执法仪")
        DashcamForeground.setActive(false)
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
