package com.powerchina.zhixun

import android.app.Application
import android.util.Log
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig
import com.powerchina.zhixun.dashcam.VideoKeyHandler
import com.powerchina.zhixun.dashcam.VideoKeyReceiver
import com.powerchina.zhixun.dashcam.VideoKeyRegistrar
import com.powerchina.zhixun.physicalkey.PhysicalKeyLifecycle
import com.powerchina.zhixun.location.BaiduSdkInitializer
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeForegroundService
import com.powerchina.zhixun.xiaozhi.XiaozhiLifecycle
import com.powerchina.zhixun.xiaozhi.XiaozhiMcpHandler
import com.powerchina.zhixun.xiaozhi.XiaozhiPhotoCoordinator

class ZhiXunApplication : Application(), CameraXConfig.Provider {

    override fun onCreate() {
        super.onCreate()
        BaiduSdkInitializer.ensureInitialized(this)
        PhysicalKeyLifecycle.register(this)
        Log.i(
            VideoKeyReceiver.TAG,
            "物理键拦截已启用 (KeyEvent + 录音键广播 + 录像键广播)",
        )
        VideoKeyRegistrar.register(this)
        VideoKeyHandler.logTestCommands(this)
        XiaozhiLifecycle.register(this)
        XiaozhiPhotoCoordinator.register(this)
        XiaozhiMcpHandler.register(this)
        XiaozhiWakeForegroundService.ensureStarted(this)
    }

    override fun getCameraXConfig(): CameraXConfig {
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setMinimumLoggingLevel(Log.ERROR)
            .build()
    }
}
