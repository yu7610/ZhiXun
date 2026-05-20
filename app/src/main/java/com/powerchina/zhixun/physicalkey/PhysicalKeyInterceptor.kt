package com.powerchina.zhixun.physicalkey

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import com.powerchina.zhixun.dashcam.DashcamVideoKeyEvents
import com.powerchina.zhixun.dashcam.VideoKeyHandler
import com.powerchina.zhixun.dashcam.VideoKeyReceiver

/**
 * 应用在前台时拦截机身物理键，阻止系统默认行为，仅走应用内定制逻辑。
 */
object PhysicalKeyInterceptor {

    private const val TAG = VideoKeyReceiver.TAG
    private const val LONG_PRESS_MS = 600L

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    private var longPressTriggered = false

    @Volatile
    var isAppInForeground: Boolean = false
        private set

    fun setAppInForeground(inForeground: Boolean) {
        isAppInForeground = inForeground
        if (!inForeground) {
            cancelPendingLongPress()
        }
        Log.d(TAG, "物理键拦截: 前台=${inForeground}")
    }

    /**
     * @return true 表示已消费，不再交给系统/Activity 默认处理
     */
    fun dispatchKeyEvent(context: Context, event: KeyEvent): Boolean {
        if (!isAppInForeground) return false
        if (!shouldInterceptKeyEvent(event)) return false

        val keyCode = event.keyCode
        if (isVideoRelatedKey(keyCode)) {
            return dispatchVideoKeyEvent(context, event)
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.i(
                TAG,
                "已拦截未映射物理键 keyCode=$keyCode(${KeyEvent.keyCodeToString(keyCode)}) " +
                    "scanCode=${event.scanCode}",
            )
        }
        return true
    }

    /**
     * @return true 表示已处理并应阻断后续接收器（有序广播）
     */
    fun dispatchVideoBroadcast(context: Context, intent: Intent?): Boolean {
        if (!isAppInForeground) return false
        return VideoKeyHandler.handleBroadcast(context, intent)
    }

    private fun dispatchVideoKeyEvent(context: Context, event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true
                longPressTriggered = false
                cancelPendingLongPress()
                longPressRunnable = Runnable {
                    longPressTriggered = true
                    Log.i(
                        TAG,
                        "KeyEvent 长按 keyCode=${event.keyCode} " +
                            "(${KeyEvent.keyCodeToString(event.keyCode)})",
                    )
                    VideoKeyHandler.handleKeyEvent(context, DashcamVideoKeyEvents.KeyAction.LONG_PRESS)
                }.also { handler.postDelayed(it, LONG_PRESS_MS) }
                Log.d(
                    TAG,
                    "KeyEvent 按下 keyCode=${event.keyCode} " +
                        "(${KeyEvent.keyCodeToString(event.keyCode)})",
                )
                return true
            }

            KeyEvent.ACTION_UP -> {
                cancelPendingLongPress()
                if (!longPressTriggered) {
                    Log.i(
                        TAG,
                        "KeyEvent 短按 keyCode=${event.keyCode} " +
                            "(${KeyEvent.keyCodeToString(event.keyCode)})",
                    )
                    VideoKeyHandler.handleKeyEvent(context, DashcamVideoKeyEvents.KeyAction.PRESS)
                }
                longPressTriggered = false
                return true
            }
        }
        return true
    }

    private fun cancelPendingLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
    }

    /**
     * 放行返回、音量等系统常用键；其余机身硬键在前台一律拦截。
     */
    fun shouldInterceptKeyEvent(event: KeyEvent): Boolean {
        if (isVirtualKeyEvent(event)) return false

        val keyCode = event.keyCode
        if (keyCode in PASSTHROUGH_KEY_CODES) return false

        if (isVideoRelatedKey(keyCode)) return true
        if (keyCode in HARDWARE_KEY_CODES) return true

        // 执法仪部分按键上报为 UNKNOWN + scanCode
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && event.scanCode != 0) return true

        // 外接实体键盘输入不拦截
        if (event.isFromSource(InputDevice.SOURCE_KEYBOARD) &&
            event.device != null &&
            event.device?.isVirtual == false &&
            event.device?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        ) {
            return false
        }

        return false
    }

    fun isVideoRelatedKey(keyCode: Int): Boolean = keyCode in VIDEO_KEY_CODES

    private val PASSTHROUGH_KEY_CODES = setOf(
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER,
    )

    private val VIDEO_KEY_CODES = setOf(
        KeyEvent.KEYCODE_CAMERA,
        KeyEvent.KEYCODE_MEDIA_RECORD,
        KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU,
        KeyEvent.KEYCODE_BUTTON_MODE,
        KeyEvent.KEYCODE_F12,
        289,
        290,
    )

    /** 常见执法仪侧键 / 功能键（拦截后若无映射则仅消费，不触发系统） */
    private val HARDWARE_KEY_CODES = setOf(
        KeyEvent.KEYCODE_F1,
        KeyEvent.KEYCODE_F2,
        KeyEvent.KEYCODE_F3,
        KeyEvent.KEYCODE_F4,
        KeyEvent.KEYCODE_F5,
        KeyEvent.KEYCODE_F6,
        KeyEvent.KEYCODE_F7,
        KeyEvent.KEYCODE_F8,
        KeyEvent.KEYCODE_F9,
        KeyEvent.KEYCODE_F10,
        KeyEvent.KEYCODE_F11,
        KeyEvent.KEYCODE_FOCUS,
        KeyEvent.KEYCODE_CALL,
        KeyEvent.KEYCODE_ENDCALL,
        KeyEvent.KEYCODE_VOICE_ASSIST,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        230, // KEYCODE_PTT（部分 API 无常量）
        131, 132, 133, 134, 135, // 部分定制机
    )

    private fun isVirtualKeyEvent(event: KeyEvent): Boolean {
        return (event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0
    }
}
