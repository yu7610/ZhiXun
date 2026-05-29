package com.powerchina.zhixun.location

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.powerchina.zhixun.ui.theme.YTheme

/** 定位轨迹页（百度地图） */
class LocationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BaiduSdkInitializer.ensureInitialized(application)
        enableEdgeToEdge()
        setContent {
            YTheme(darkTheme = true) {
                LocationScreen(onBack = { finish() })
            }
        }
    }
}
