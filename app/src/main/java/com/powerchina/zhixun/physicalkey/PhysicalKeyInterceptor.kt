package com.powerchina.zhixun.physicalkey

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import com.powerchina.zhixun.dashcam.PhysicalKeyCodes
import com.powerchina.zhixun.dashcam.VideoKeyHandler
import com.powerchina.zhixun.dashcam.VideoKeyReceiver

/**
 * 应用在前台时拦截机身物理键，阻止系统默认行为，仅走应用内定制逻辑。
 */
object PhysicalKeyInterceptor {

    private const val TAG = VideoKeyReceiver.TAG

    @Volatile
    var isAppInForeground: Boolean = false
        private set

    fun setAppInForeground(inForeground: Boolean) {
        isAppInForeground = inForeground
        Log.d(TAG, "物理键拦截: 前台=${inForeground}")
    }

    /**
     * @return true 表示已消费，不再交给系统/Activity 默认处理
     */
    fun dispatchKeyEvent(context: Context, event: KeyEvent): Boolean {
        if (!isAppInForeground) return false
        if (!shouldInterceptKeyEvent(event)) return false

        val keyCode = event.keyCode
        val scanCode = event.scanCode
        if (PhysicalKeyCodes.isRecordKey(keyCode, scanCode)) {
            return dispatchBlockedKeyEvent(context, event)
        }
        if (PhysicalKeyCodes.isMappedKey(keyCode)) {
            return dispatchMappedKeyEvent(context, event)
        }
        if (isLegacyVideoRelatedKey(keyCode)) {
            return dispatchLegacyVideoKeyEvent(context, event)
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

    /** keyCode=142 拍照、keyCode=136 录像：抬起时触发，不做短长按区分 */
    private fun dispatchMappedKeyEvent(context: Context, event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    Log.d(
                        TAG,
                        "KeyEvent 按下 keyCode=${event.keyCode} " +
                            "(${KeyEvent.keyCodeToString(event.keyCode)})",
                    )
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                Log.i(
                    TAG,
                    "KeyEvent 抬起 keyCode=${event.keyCode} " +
                        "(${KeyEvent.keyCodeToString(event.keyCode)})",
                )
                VideoKeyHandler.handleKeyCode(context, event.keyCode)
                return true
            }
        }
        return true
    }

    /** keyCode=138（KEYCODE_F7）录音键：待机/断开时连接并开麦 */
    private fun dispatchBlockedKeyEvent(context: Context, event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    Log.i(
                        TAG,
                        "录音键按下 keyCode=${event.keyCode} " +
                            "(${KeyEvent.keyCodeToString(event.keyCode)}) scanCode=${event.scanCode}",
                    )
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                RecordKeyHandler.handleKeyUp(context)
                return true
            }
        }
        return true
    }

    /** 其它录像相关键：保留短按/长按兼容 */
    private fun dispatchLegacyVideoKeyEvent(context: Context, event: KeyEvent): Boolean {
        // 未映射的 legacy 键仅消费，避免触发系统行为
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.d(
                TAG,
                "Legacy 录像键按下 keyCode=${event.keyCode} " +
                    "(${KeyEvent.keyCodeToString(event.keyCode)})",
            )
        }
        return true
    }

    /**
     * 放行返回、音量等系统常用键；其余机身硬键在前台一律拦截。
     */
    fun shouldInterceptKeyEvent(event: KeyEvent): Boolean {
        if (isVirtualKeyEvent(event)) return false

        val keyCode = event.keyCode
        if (keyCode in PASSTHROUGH_KEY_CODES) return false

        if (PhysicalKeyCodes.isMappedKey(keyCode)) return true
        if (PhysicalKeyCodes.isRecordKey(keyCode, event.scanCode)) return true
        if (isLegacyVideoRelatedKey(keyCode)) return true
        if (keyCode in HARDWARE_KEY_CODES) return true

        if (PhysicalKeyCodes.isRecordKey(keyCode, event.scanCode)) return true
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && event.scanCode != 0) return true

        if (event.isFromSource(InputDevice.SOURCE_KEYBOARD) &&
            event.device != null &&
            event.device?.isVirtual == false &&
            event.device?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        ) {
            return false
        }

        return false
    }

    fun isVideoRelatedKey(keyCode: Int): Boolean =
        PhysicalKeyCodes.isMappedKey(keyCode) || isLegacyVideoRelatedKey(keyCode)

    private fun isLegacyVideoRelatedKey(keyCode: Int): Boolean = keyCode in LEGACY_VIDEO_KEY_CODES

    private val PASSTHROUGH_KEY_CODES = setOf(
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_POWER,
    )

    private val LEGACY_VIDEO_KEY_CODES = setOf(
        KeyEvent.KEYCODE_CAMERA,
        KeyEvent.KEYCODE_MEDIA_RECORD,
        KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU,
        KeyEvent.KEYCODE_BUTTON_MODE,
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
        PhysicalKeyCodes.SYSTEM_BLOCK,
        230,
        131, 132, 133, 134, 135,
    )

    private fun isVirtualKeyEvent(event: KeyEvent): Boolean {
        return (event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY) != 0
    }
}
