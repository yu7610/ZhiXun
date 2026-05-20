package com.powerchina.zhixun.xiaozhi

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * 应用进入前台时连接小智，退到后台/关闭时断开并停止服务。
 */
object XiaozhiLifecycle {

    private const val TAG = "XiaozhiLifecycle"

    fun register(application: Application) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                Log.d(TAG, "应用进入前台，启动小智连接")
                XiaozhiSessionManager.getInstance(application).ensureConnected()
            }

            override fun onStop(owner: LifecycleOwner) {
                Log.d(TAG, "应用进入后台/关闭，断开小智")
                shutdown(application)
            }
        })
    }

    fun shutdown(application: Application) {
        XiaozhiSessionManager.getInstance(application).shutdown()
    }
}
