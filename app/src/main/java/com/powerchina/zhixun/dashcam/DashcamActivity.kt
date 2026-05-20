package com.powerchina.zhixun.dashcam

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.powerchina.zhixun.util.ScreenOnHelper

/** 执法拍摄 / 行车记录仪独立页面 */
class DashcamActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ScreenOnHelper.keepScreenOn(this)
        setContent {
            DashcamScreen(onBack = { finish() })
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (VideoKeyActivityHelper.dispatchKeyEvent(this, event)) return true
        return super.dispatchKeyEvent(event)
    }
}
