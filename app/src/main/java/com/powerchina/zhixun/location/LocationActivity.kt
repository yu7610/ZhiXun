package com.powerchina.zhixun.location

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.powerchina.zhixun.dashcam.VideoKeyActivityHelper
import com.powerchina.zhixun.ui.theme.YTheme
import com.powerchina.zhixun.util.ScreenOnHelper
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeForegroundService

/** 定位轨迹页（百度地图） */
class LocationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BaiduSdkInitializer.ensureInitialized(application)
        enableEdgeToEdge()
        ScreenOnHelper.attach(this)
        XiaozhiWakeForegroundService.ensureStarted(this)
        setContent {
            YTheme(darkTheme = true) {
                LocationScreen(onBack = { finish() })
            }
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
