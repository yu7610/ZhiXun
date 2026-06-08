package com.powerchina.zhixun.util

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import java.lang.ref.WeakReference

/**
 * 有用户操作时保持亮屏；连续 [IDLE_SCREEN_OFF_MS] 无操作后允许系统息屏。
 */
object ScreenOnHelper {

    private const val TAG = "ScreenOnHelper"
    private const val IDLE_SCREEN_OFF_MS = 5 * 60 * 1000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var attachedActivity: WeakReference<Activity>? = null
    private var idleRunnable: Runnable? = null

    fun attach(activity: Activity) {
        detach(activity, clearFlag = false)
        attachedActivity = WeakReference(activity)
        keepScreenOn(activity)
        scheduleIdleScreenOff(activity)
        Log.d(TAG, "attach ${activity.javaClass.simpleName}，${IDLE_SCREEN_OFF_MS / 1000}s 无操作允许息屏")
    }

    fun onUserInteraction(activity: Activity) {
        val current = attachedActivity?.get() ?: activity
        if (current != activity) return
        keepScreenOn(activity)
        scheduleIdleScreenOff(activity)
    }

    fun detach(activity: Activity, clearFlag: Boolean = true) {
        if (attachedActivity?.get() == activity) {
            cancelIdleTimer()
            attachedActivity = null
        }
        if (clearFlag) {
            allowScreenSleep(activity)
        }
    }

    private fun scheduleIdleScreenOff(activity: Activity) {
        cancelIdleTimer()
        val runnable = Runnable {
            val stillAttached = attachedActivity?.get()
            if (stillAttached == null || stillAttached != activity || activity.isFinishing) {
                return@Runnable
            }
            allowScreenSleep(activity)
            Log.i(TAG, "${IDLE_SCREEN_OFF_MS / 1000}s 无操作，允许息屏 ${activity.javaClass.simpleName}")
        }
        idleRunnable = runnable
        mainHandler.postDelayed(runnable, IDLE_SCREEN_OFF_MS)
    }

    private fun cancelIdleTimer() {
        idleRunnable?.let { mainHandler.removeCallbacks(it) }
        idleRunnable = null
    }

    private fun keepScreenOn(activity: Activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun allowScreenSleep(activity: Activity) {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
