package com.powerchina.zhixun.util

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import java.lang.ref.WeakReference

/**
 * 有用户操作时保持亮屏；连续 [IDLE_SCREEN_OFF_MS] 无操作后允许系统息屏。
 * 待机模式下：10s 变暗，20s 黑屏（最低亮度 + 允许息屏）。
 */
object ScreenOnHelper {

    private const val TAG = "ScreenOnHelper"
    private const val IDLE_SCREEN_OFF_MS = 5 * 60 * 1000L
    private const val STANDBY_DIM_MS = 10_000L
    private const val STANDBY_OFF_MS = 20_000L
    private const val STANDBY_DIM_BRIGHTNESS = 0.12f

    private enum class StandbyPhase { NORMAL, DIM, OFF }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var attachedActivity: WeakReference<Activity>? = null
    private var idleRunnable: Runnable? = null
    private var standbyDimRunnable: Runnable? = null
    private var standbyOffRunnable: Runnable? = null

    private var inStandbyMode = false
    private var standbyPhase = StandbyPhase.NORMAL
    private var savedWindowBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

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
        if (inStandbyMode) {
            if (standbyPhase != StandbyPhase.NORMAL) {
                restoreStandbyBrightness(activity)
                Log.d(TAG, "待机交互，恢复亮度")
            }
            keepScreenOn(activity)
            resetStandbyTimers(activity)
            return
        }
        keepScreenOn(activity)
        scheduleIdleScreenOff(activity)
    }

    /** 进入待机 UI：启动 10s 变暗 / 20s 黑屏计时 */
    fun enterStandbyMode(activity: Activity) {
        if (attachedActivity?.get() != activity) return
        val firstEnter = !inStandbyMode
        inStandbyMode = true
        if (firstEnter) {
            standbyPhase = StandbyPhase.NORMAL
            keepScreenOn(activity)
            resetStandbyTimers(activity)
            Log.i(TAG, "待机计时：${STANDBY_DIM_MS / 1000}s 变暗，${STANDBY_OFF_MS / 1000}s 黑屏")
        }
    }

    /** 离开待机 UI：恢复亮度并回到常亮策略 */
    fun exitStandbyMode(activity: Activity) {
        if (!inStandbyMode) return
        if (attachedActivity?.get() != activity) return
        inStandbyMode = false
        cancelStandbyTimers()
        restoreStandbyBrightness(activity)
        keepScreenOn(activity)
        scheduleIdleScreenOff(activity)
        Log.i(TAG, "退出待机息屏计时")
    }

    fun detach(activity: Activity, clearFlag: Boolean = true) {
        if (attachedActivity?.get() == activity) {
            cancelIdleTimer()
            cancelStandbyTimers()
            inStandbyMode = false
            standbyPhase = StandbyPhase.NORMAL
            savedWindowBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            attachedActivity = null
        }
        if (clearFlag) {
            allowScreenSleep(activity)
            restoreStandbyBrightness(activity)
        }
    }

    private fun resetStandbyTimers(activity: Activity) {
        cancelStandbyTimers()
        standbyPhase = StandbyPhase.NORMAL
        val dimRunnable = Runnable {
            val stillAttached = attachedActivity?.get()
            if (!inStandbyMode || stillAttached == null || stillAttached != activity || activity.isFinishing) {
                return@Runnable
            }
            applyStandbyDim(activity)
        }
        val offRunnable = Runnable {
            val stillAttached = attachedActivity?.get()
            if (!inStandbyMode || stillAttached == null || stillAttached != activity || activity.isFinishing) {
                return@Runnable
            }
            applyStandbyOff(activity)
        }
        standbyDimRunnable = dimRunnable
        standbyOffRunnable = offRunnable
        mainHandler.postDelayed(dimRunnable, STANDBY_DIM_MS)
        mainHandler.postDelayed(offRunnable, STANDBY_OFF_MS)
    }

    private fun cancelStandbyTimers() {
        standbyDimRunnable?.let { mainHandler.removeCallbacks(it) }
        standbyOffRunnable?.let { mainHandler.removeCallbacks(it) }
        standbyDimRunnable = null
        standbyOffRunnable = null
    }

    private fun applyStandbyDim(activity: Activity) {
        val lp = activity.window.attributes
        if (savedWindowBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            savedWindowBrightness = lp.screenBrightness
        }
        lp.screenBrightness = STANDBY_DIM_BRIGHTNESS
        activity.window.attributes = lp
        standbyPhase = StandbyPhase.DIM
        Log.i(TAG, "待机 ${STANDBY_DIM_MS / 1000}s → 屏幕变暗")
    }

    private fun applyStandbyOff(activity: Activity) {
        allowScreenSleep(activity)
        val lp = activity.window.attributes
        if (savedWindowBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            savedWindowBrightness = lp.screenBrightness
        }
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
        activity.window.attributes = lp
        standbyPhase = StandbyPhase.OFF
        Log.i(TAG, "待机 ${STANDBY_OFF_MS / 1000}s → 屏幕黑屏")
    }

    private fun restoreStandbyBrightness(activity: Activity) {
        if (savedWindowBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            standbyPhase = StandbyPhase.NORMAL
            return
        }
        val lp = activity.window.attributes
        lp.screenBrightness = savedWindowBrightness
        activity.window.attributes = lp
        savedWindowBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        standbyPhase = StandbyPhase.NORMAL
    }

    private fun scheduleIdleScreenOff(activity: Activity) {
        if (inStandbyMode) return
        cancelIdleTimer()
        val runnable = Runnable {
            val stillAttached = attachedActivity?.get()
            if (stillAttached == null || stillAttached != activity || activity.isFinishing || inStandbyMode) {
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
