package com.powerchina.zhixun.util

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import java.lang.ref.WeakReference

/**
 * 屏幕策略：
 * - **非待机（聊天/聆听/说话等）**：始终 [FLAG_KEEP_SCREEN_ON]，不休眠
 * - **待机**：10s 变暗，20s 黑屏并允许系统息屏；触摸后恢复
 */
object ScreenOnHelper {

    private const val TAG = "ScreenOnHelper"
    private const val STANDBY_DIM_MS = 10_000L
    private const val STANDBY_OFF_MS = 20_000L
    private const val STANDBY_DIM_BRIGHTNESS = 0.12f

    private enum class StandbyPhase { NORMAL, DIM, OFF }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var attachedActivity: WeakReference<Activity>? = null
    private var standbyDimRunnable: Runnable? = null
    private var standbyOffRunnable: Runnable? = null

    private var inStandbyMode = false
    private var standbyPhase = StandbyPhase.NORMAL
    private var savedWindowBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    /** 用户设定的窗口亮度 0~1；待机恢复时优先还原到此值 */
    @Volatile
    private var userPreferredBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

    /** 待机黑屏休眠 / 亮屏恢复时通知业务层（断连、重连小智等） */
    interface StandbyScreenListener {
        /** 待机 [STANDBY_OFF_MS] 黑屏，允许系统息屏 */
        fun onStandbyScreenSleep()
        /** 用户交互恢复亮度（从变暗/黑屏恢复） */
        fun onStandbyScreenWake(fromSleep: Boolean)
    }

    @Volatile
    private var standbyScreenListener: StandbyScreenListener? = null

    fun setStandbyScreenListener(listener: StandbyScreenListener?) {
        standbyScreenListener = listener
    }

    fun attach(activity: Activity) {
        detach(activity, clearFlag = false)
        attachedActivity = WeakReference(activity)
        // 默认常亮；仅 enterStandbyMode 后才允许息屏
        keepScreenOn(activity)
        // 恢复用户设定亮度（Activity 重建后窗口亮度会丢）
        if (userPreferredBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            val lp = activity.window.attributes
            lp.screenBrightness = userPreferredBrightness
            activity.window.attributes = lp
        }
        Log.d(TAG, "attach ${activity.javaClass.simpleName}，非待机常亮")
    }

    fun onUserInteraction(activity: Activity) {
        val current = attachedActivity?.get() ?: activity
        if (current != activity) return
        if (inStandbyMode) {
            if (standbyPhase != StandbyPhase.NORMAL) {
                val fromSleep = standbyPhase == StandbyPhase.OFF
                restoreStandbyBrightness(activity)
                standbyScreenListener?.onStandbyScreenWake(fromSleep)
                Log.d(TAG, "待机交互，恢复亮度 fromSleep=$fromSleep")
            }
            keepScreenOn(activity)
            resetStandbyTimers(activity)
            return
        }
        keepScreenOn(activity)
    }

    /** 进入待机 UI：启动 10s 变暗 / 20s 黑屏计时 */
    fun enterStandbyMode(activity: Activity) {
        if (attachedActivity?.get() != activity) {
            attachedActivity = WeakReference(activity)
        }
        val firstEnter = !inStandbyMode
        inStandbyMode = true
        if (firstEnter) {
            standbyPhase = StandbyPhase.NORMAL
            keepScreenOn(activity)
            resetStandbyTimers(activity)
            Log.i(TAG, "待机计时：${STANDBY_DIM_MS / 1000}s 变暗，${STANDBY_OFF_MS / 1000}s 黑屏")
        }
    }

    /** 离开待机 UI：恢复亮度并常亮（聊天中不休眠） */
    fun exitStandbyMode(activity: Activity) {
        if (!inStandbyMode) {
            keepScreenOn(activity)
            return
        }
        if (attachedActivity?.get() != activity) return
        inStandbyMode = false
        cancelStandbyTimers()
        restoreStandbyBrightness(activity)
        keepScreenOn(activity)
        Log.i(TAG, "退出待机 → 聊天常亮")
    }

    fun detach(activity: Activity, clearFlag: Boolean = true) {
        if (attachedActivity?.get() == activity) {
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

    fun attachedActivityOrNull(): Activity? = attachedActivity?.get()

    /**
     * 设置用户亮度（0~1），立即作用到当前 Activity 窗口。
     * 不依赖 WRITE_SETTINGS；待机恢复也会回到此亮度。
     */
    fun applyUserBrightness(activity: Activity, brightness01: Float) {
        val value = brightness01.coerceIn(0.01f, 1f)
        userPreferredBrightness = value
        // 待机变暗/黑屏前已缓存的「正常亮度」一并更新，避免恢复到旧值
        if (savedWindowBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            savedWindowBrightness = value
        }
        if (standbyPhase == StandbyPhase.OFF) {
            Log.i(TAG, "用户设亮度 $value，当前黑屏待机，唤醒后生效")
            return
        }
        val lp = activity.window.attributes
        lp.screenBrightness = value
        activity.window.attributes = lp
        // 若正处于待机变暗，直接回到正常相位并重置计时
        if (inStandbyMode && standbyPhase == StandbyPhase.DIM) {
            standbyPhase = StandbyPhase.NORMAL
            resetStandbyTimers(activity)
        }
        Log.i(TAG, "窗口亮度已设为 $value")
    }

    fun currentUserBrightnessOrNone(): Float = userPreferredBrightness

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
        standbyScreenListener?.onStandbyScreenSleep()
    }

    private fun restoreStandbyBrightness(activity: Activity) {
        val target = when {
            userPreferredBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE ->
                userPreferredBrightness
            savedWindowBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE ->
                savedWindowBrightness
            else -> {
                standbyPhase = StandbyPhase.NORMAL
                return
            }
        }
        val lp = activity.window.attributes
        lp.screenBrightness = target
        activity.window.attributes = lp
        savedWindowBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        standbyPhase = StandbyPhase.NORMAL
    }

    private fun keepScreenOn(activity: Activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun allowScreenSleep(activity: Activity) {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
