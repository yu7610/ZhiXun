package com.powerchina.zhixun.physicalkey

import android.content.Context
import android.content.Intent
import android.util.Log
import com.powerchina.zhixun.MainActivity
import com.powerchina.zhixun.dashcam.VideoKeyReceiver
import com.powerchina.zhixun.xiaozhi.XiaozhiAppEvents

/**
 * 物理录音键（keyCode=138）：待机时进入聆听；聆听/说话时结束对话进入待机。
 *
 * OEM 可能同时发 LONG_PRESS 广播与 KeyEvent ACTION_UP，需去重。
 */
object RecordKeyHandler {

    private const val DEBOUNCE_MS = 800L
    /** LONG_PRESS 已处理后，忽略随后 KEY_UP 释放（同一次按压） */
    private const val LONG_PRESS_SUPPRESS_KEY_UP_MS = 1500L

    private var lastHandleAtMs = 0L
    private var longPressHandledAtMs = 0L

    fun handleLongPress(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastHandleAtMs < DEBOUNCE_MS) {
            Log.d(VideoKeyReceiver.TAG, "录音键 LONG_PRESS debounce 忽略")
            return
        }
        lastHandleAtMs = now
        longPressHandledAtMs = now
        Log.i(VideoKeyReceiver.TAG, "keyCode=138 录音键 LONG_PRESS -> 切换对话")
        dispatchVoiceKey(context)
    }

    fun handleKeyUp(context: Context) {
        val now = System.currentTimeMillis()
        if (now - longPressHandledAtMs < LONG_PRESS_SUPPRESS_KEY_UP_MS) {
            Log.d(VideoKeyReceiver.TAG, "录音键 KEY_UP 已由 LONG_PRESS 处理，忽略")
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
