package com.powerchina.zhixun.dashcam

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 物理录像键统一处理（广播 / 按键事件）。
 */
object VideoKeyHandler {

    private const val DEBOUNCE_MS = 400L
    private var lastHandleAtMs = 0L

    fun handleBroadcast(context: Context, intent: Intent?): Boolean {
        Log.d(VideoKeyReceiver.TAG, "handleBroadcast: intent=$intent action=${intent?.action} extras=${intent?.extras}")
        val action = intent?.action
        if (action == null) {
            Log.w(VideoKeyReceiver.TAG, "handleBroadcast: action 为空")
            return false
        }
        val keyAction = resolveKeyAction(action)
        if (keyAction == null) {
            Log.w(VideoKeyReceiver.TAG, "handleBroadcast: 未识别的 action=$action")
            return false
        }
        return dispatch(context, keyAction, "Broadcast:$action")
    }

    fun handleKeyEvent(context: Context, keyAction: DashcamVideoKeyEvents.KeyAction): Boolean {
        return dispatch(context, keyAction, "KeyEvent")
    }

    private fun dispatch(
        context: Context,
        keyAction: DashcamVideoKeyEvents.KeyAction,
        source: String,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastHandleAtMs < DEBOUNCE_MS) {
            Log.d(VideoKeyReceiver.TAG, "忽略重复按键 source=$source 间隔=${now - lastHandleAtMs}ms")
            return true
        }
        lastHandleAtMs = now

        Log.i(VideoKeyReceiver.TAG, "收到物理录像键 source=$source -> $keyAction")
        DashcamVideoKeyEvents.emit(keyAction)

        val launchIntent = Intent(context, DashcamActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        Log.d(VideoKeyReceiver.TAG, "启动 DashcamActivity")
        context.startActivity(launchIntent)
        return true
    }

    fun resolveKeyAction(action: String): DashcamVideoKeyEvents.KeyAction? {
        return when (action) {
            VideoKeyReceiver.ACTION_PRESS_VIDEO_KEY,
            "intent.action.PRESS_VIDEO_KEY",
            "com.android.intent.action.PRESS_VIDEO_KEY",
            -> DashcamVideoKeyEvents.KeyAction.PRESS

            VideoKeyReceiver.ACTION_LONG_PRESS_VIDEO_KEY,
            "intent.action.LONG_PRESS_VIDEO_KEY",
            "com.android.intent.action.LONG_PRESS_VIDEO_KEY",
            -> DashcamVideoKeyEvents.KeyAction.LONG_PRESS

            else -> null
        }
    }

    fun logTestCommands(context: Context) {
        val pkg = context.packageName
        Log.i(
            VideoKeyReceiver.TAG,
            "若按物理键无日志，请用 adb 测试广播是否可达:\n" +
                "  adb shell am broadcast -a ${VideoKeyReceiver.ACTION_PRESS_VIDEO_KEY} -p $pkg\n" +
                "  adb shell am broadcast -a ${VideoKeyReceiver.ACTION_LONG_PRESS_VIDEO_KEY} -p $pkg",
        )
    }
}
