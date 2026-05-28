package com.powerchina.zhixun.dashcam

import android.content.Context
import android.content.Intent
import android.util.Log
import com.powerchina.zhixun.MainActivity
import com.powerchina.zhixun.physicalkey.PhysicalKeyInterceptor
import com.powerchina.zhixun.xiaozhi.XiaozhiAppEvents

/**
 * 物理键统一处理（广播 / KeyEvent）。
 */
object VideoKeyHandler {

    private const val DEBOUNCE_MS = 400L
    private var lastHandleAtMs = 0L

    fun handleBroadcast(context: Context, intent: Intent?): Boolean {
        Log.d(
            VideoKeyReceiver.TAG,
            "handleBroadcast: intent=$intent action=${intent?.action} extras=${intent?.extras}",
        )
        val action = intent?.action ?: return false.also {
            Log.w(VideoKeyReceiver.TAG, "handleBroadcast: action 为空")
        }
        val keyAction = resolveKeyAction(action) ?: return false.also {
            Log.w(VideoKeyReceiver.TAG, "handleBroadcast: 未识别的 action=$action")
        }
        return dispatch(context, keyAction, keyCode = null, source = "Broadcast:$action")
    }

    fun handleKeyEvent(context: Context, keyAction: DashcamVideoKeyEvents.KeyAction): Boolean {
        return dispatch(context, keyAction, keyCode = null, source = "KeyEvent")
    }

    fun handleKeyCode(context: Context, keyCode: Int): Boolean {
        val keyAction = PhysicalKeyCodes.actionForKeyCode(keyCode) ?: return false
        return dispatch(context, keyAction, keyCode = keyCode, source = "KeyEvent")
    }

    private fun dispatch(
        context: Context,
        keyAction: DashcamVideoKeyEvents.KeyAction,
        keyCode: Int?,
        source: String,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastHandleAtMs < DEBOUNCE_MS) {
            Log.d(
                VideoKeyReceiver.TAG,
                "忽略重复按键 source=$source keyCode=$keyCode 间隔=${now - lastHandleAtMs}ms",
            )
            return true
        }
        lastHandleAtMs = now

        Log.i(
            VideoKeyReceiver.TAG,
            "收到物理键 source=$source keyCode=$keyCode -> $keyAction",
        )
        return when (keyAction) {
            DashcamVideoKeyEvents.KeyAction.PHOTO -> {
                Log.d(VideoKeyReceiver.TAG, "keyCode=${PhysicalKeyCodes.PHOTO} 拍照键 -> 发送拍照指令")
                dispatchPhotoKey(context)
                true
            }
            DashcamVideoKeyEvents.KeyAction.RECORD -> {
                DashcamVideoKeyEvents.emit(keyAction)
                val launchIntent = Intent(context, DashcamActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                Log.d(VideoKeyReceiver.TAG, "keyCode=${PhysicalKeyCodes.RECORD} 录像 -> DashcamActivity")
                context.startActivity(launchIntent)
                true
            }
        }
    }

    fun resolveKeyAction(action: String): DashcamVideoKeyEvents.KeyAction? {
        return when (action) {
            VideoKeyReceiver.ACTION_PRESS_VIDEO_KEY,
            "intent.action.PRESS_VIDEO_KEY",
            "com.android.intent.action.PRESS_VIDEO_KEY",
            -> DashcamVideoKeyEvents.KeyAction.PHOTO

            VideoKeyReceiver.ACTION_LONG_PRESS_VIDEO_KEY,
            "intent.action.LONG_PRESS_VIDEO_KEY",
            "com.android.intent.action.LONG_PRESS_VIDEO_KEY",
            -> DashcamVideoKeyEvents.KeyAction.RECORD

            else -> null
        }
    }

    private fun dispatchPhotoKey(context: Context) {
        if (PhysicalKeyInterceptor.isAppInForeground) {
            XiaozhiAppEvents.requestPhotoKeyPress()
            return
        }
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_XIAOZHI, true)
            putExtra(MainActivity.EXTRA_AUTO_CONNECT, true)
            putExtra(MainActivity.EXTRA_PHOTO_KEY, true)
        }
        context.startActivity(launchIntent)
    }

    fun logTestCommands(context: Context) {
        val pkg = context.packageName
        Log.i(
            VideoKeyReceiver.TAG,
            "物理键映射: keyCode=${PhysicalKeyCodes.PHOTO}(拍照) keyCode=${PhysicalKeyCodes.RECORD}(录像)\n" +
                "广播测试:\n" +
                "  adb shell am broadcast -a ${VideoKeyReceiver.ACTION_PRESS_VIDEO_KEY} -p $pkg\n" +
                "  adb shell am broadcast -a ${VideoKeyReceiver.ACTION_LONG_PRESS_VIDEO_KEY} -p $pkg",
        )
    }
}
