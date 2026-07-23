package com.powerchina.zhixun.physicalkey

import android.content.Context
import android.content.Intent
import android.util.Log
import com.powerchina.zhixun.MainActivity
import com.powerchina.zhixun.dashcam.VideoKeyReceiver
import com.powerchina.zhixun.xiaozhi.XiaozhiAppEvents

/**
 * 物理录音键（keyCode=138）：短按抬起进入/结束对话；长按仅拦截系统，不做业务处理。
 *
 * OEM 可能同时发 LONG_PRESS 广播与 KeyEvent ACTION_UP，长按后需抑制随后 KEY_UP。
 */
object RecordKeyHandler {

    private const val DEBOUNCE_MS = 800L
    /** 长按已拦截后，忽略随后 KEY_UP（同一次按压） */
    private const val LONG_PRESS_SUPPRESS_KEY_UP_MS = 1500L

    private var lastHandleAtMs = 0L
    private var longPressInterceptedAtMs = 0L

    /** 长按广播：只记录时间戳，不触发对话 */
    fun markLongPressIntercepted() {
        longPressInterceptedAtMs = System.currentTimeMillis()
        Log.i(VideoKeyReceiver.TAG, "keyCode=138 录音键 LONG_PRESS 已拦截，不做处理")
    }

    @Deprecated("长按不再触发对话，请用 markLongPressIntercepted()", ReplaceWith("markLongPressIntercepted()"))
    fun handleLongPress(@Suppress("UNUSED_PARAMETER") context: Context) {
        markLongPressIntercepted()
    }

    fun handleKeyUp(context: Context) {
        val now = System.currentTimeMillis()
        if (now - longPressInterceptedAtMs < LONG_PRESS_SUPPRESS_KEY_UP_MS) {
            Log.d(VideoKeyReceiver.TAG, "录音键 KEY_UP 跟随长按，忽略")
            return
        }
        if (now - lastHandleAtMs < DEBOUNCE_MS) {
            Log.d(VideoKeyReceiver.TAG, "录音键 KEY_UP debounce 忽略")
            return
        }
        lastHandleAtMs = now
        Log.i(VideoKeyReceiver.TAG, "keyCode=138 录音键 KEY_UP -> 切换对话")
        dispatchVoiceKey(context)
    }

    private fun dispatchVoiceKey(context: Context) {
        if (PhysicalKeyInterceptor.isAppInForeground) {
            // 前台：只发 AppEvents，不 restart Activity，避免 ON_PAUSE 二次触发开/关麦
            XiaozhiAppEvents.requestVoiceConversation()
            return
        }
        // 后台：拉起 Activity，由 Intent 统一触发一次开麦
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_XIAOZHI, true)
            putExtra(MainActivity.EXTRA_AUTO_CONNECT, true)
            putExtra(MainActivity.EXTRA_START_VOICE, true)
        }
        context.startActivity(launchIntent)
    }
}
