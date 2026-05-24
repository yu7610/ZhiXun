package com.powerchina.zhixun.xiaozhi

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeCoordinator
import com.powerchina.zhixun.xiaozhi.wake.XiaozhiWakeForegroundService

/**
 * 应用生命周期：前台保持/恢复连接，退后台也不断开 WebSocket。
 */
object XiaozhiLifecycle {

    private const val TAG = "Lifecycle"

    fun register(application: Application) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                Log.d(TAG, "应用进入前台 → ensureConnected + 唤醒监听")
                XiaozhiSessionManager.getInstance(application).ensureConnected()
                if (!XiaozhiWakeCoordinator.isWakeHandoffInProgress()) {
                    XiaozhiWakeForegroundService.ensureListeningActive(application)
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                Log.d(TAG, "应用进入后台，保持 WebSocket 连接")
                XiaozhiSessionManager.getInstance(application).ensureConnected()
                if (XiaozhiWakeCoordinator.isWakeHandoffInProgress()) {
                    Log.d(TAG, "唤醒交接中，不恢复后台监听")
                    return
                }
                XiaozhiWakeForegroundService.ensureStarted(application)
                XiaozhiWakeForegroundService.ensureListeningActive(application)
            }
        })
    }

    fun shutdown(application: Application) {
        XiaozhiSessionManager.getInstance(application).shutdown()
    }
}
