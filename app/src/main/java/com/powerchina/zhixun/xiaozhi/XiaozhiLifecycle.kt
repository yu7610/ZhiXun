package com.powerchina.zhixun.xiaozhi

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeCoordinator
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeForegroundService

/**
 * 应用进入前台时连接小智，退到后台/关闭时断开并停止服务。
 */
object XiaozhiLifecycle {

    private const val TAG = "Lifecycle"

    fun register(application: Application) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                Log.d(TAG, "应用进入前台 → ensureConnected")
                XiaozhiSessionManager.getInstance(application).ensureConnected()
            }

            override fun onStop(owner: LifecycleOwner) {
                if (XiaozhiWakeForegroundService.isRunning()) {
                    Log.d(TAG, "应用进入后台，唤醒服务运行中 → 保持 WebSocket + 唤醒")
                    if (!XiaozhiWakeCoordinator.isWakeHandoffInProgress()) {
                        XiaozhiWakeForegroundService.ensureListeningActive(application)
                    } else {
                        Log.d(TAG, "唤醒交接中，不恢复后台监听")
                    }
                    return
                }
                Log.d(TAG, "应用进入后台，无唤醒服务 → shutdown")
                shutdown(application)
            }
        })
    }

    fun shutdown(application: Application) {
        XiaozhiSessionManager.getInstance(application).shutdown()
    }
}
