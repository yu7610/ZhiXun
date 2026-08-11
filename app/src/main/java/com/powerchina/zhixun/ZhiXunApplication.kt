package com.powerchina.zhixun

import android.app.Application
import android.util.Log
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig
import com.powerchina.zhixun.dashcam.VideoKeyHandler
import com.powerchina.zhixun.dashcam.VideoKeyReceiver
import com.powerchina.zhixun.dashcam.VideoKeyRegistrar
import com.powerchina.zhixun.dashcam.VideoUploadCoordinator
import com.powerchina.zhixun.physicalkey.PhysicalKeyLifecycle
import com.powerchina.zhixun.location.LocationBootstrap
import com.powerchina.zhixun.xiaozhi.wake.OfflineWakeDetector
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeForegroundService
import com.powerchina.zhixun.xiaozhi.XiaozhiLifecycle
import com.powerchina.zhixun.xiaozhi.XiaozhiMcpHandler
import com.tencent.bugly.crashreport.CrashReport

class ZhiXunApplication : Application(), CameraXConfig.Provider {

    override fun onCreate() {
        super.onCreate()
        // 第三参数 true：Logcat 输出详细日志，崩溃立即上报（Debug/Release 一致）
        CrashReport.initCrashReport(applicationContext, BUGLY_APP_ID, true)
        // 仅初始化百度 SDK；连续定位/上报在进入定位页时启动
        LocationBootstrap.initialize(this)
        PhysicalKeyLifecycle.register(this)
        VideoUploadCoordinator.init(this)
        Log.i(
            VideoKeyReceiver.TAG,
            "物理键拦截已启用 (KeyEvent + 录音键广播 + 录像键广播)",
        )
        VideoKeyRegistrar.register(this)
        VideoKeyHandler.logTestCommands(this)
        XiaozhiLifecycle.register(this)
        XiaozhiMcpHandler.register(this)
        OfflineWakeDetector.prewarm(this)
        XiaozhiWakeForegroundService.ensureStarted(this)
    }

    override fun getCameraXConfig(): CameraXConfig {
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setMinimumLoggingLevel(Log.ERROR)
            .build()
    }

    companion object {
        private const val BUGLY_APP_ID = "5f779d26af"
    }
}
