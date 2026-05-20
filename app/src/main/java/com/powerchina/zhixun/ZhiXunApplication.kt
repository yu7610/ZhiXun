package com.powerchina.zhixun

import android.app.Application
import android.util.Log
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig
import com.powerchina.zhixun.dashcam.VideoKeyHandler
import com.powerchina.zhixun.dashcam.VideoKeyReceiver
import com.powerchina.zhixun.dashcam.VideoKeyRegistrar
import com.powerchina.zhixun.physicalkey.PhysicalKeyLifecycle
import com.powerchina.zhixun.xiaozhi.XiaozhiLifecycle

class ZhiXunApplication : Application(), CameraXConfig.Provider {

    override fun onCreate() {
        super.onCreate()
        PhysicalKeyLifecycle.register(this)
        Log.i(
            VideoKeyReceiver.TAG,
            "物理键拦截已启用 (前台拦截 + 广播 + KeyEvent)",
        )
        VideoKeyRegistrar.register(this)
        VideoKeyHandler.logTestCommands(this)
        XiaozhiLifecycle.register(this)
    }

    override fun getCameraXConfig(): CameraXConfig {
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setMinimumLoggingLevel(Log.ERROR)
            .build()
    }
}
