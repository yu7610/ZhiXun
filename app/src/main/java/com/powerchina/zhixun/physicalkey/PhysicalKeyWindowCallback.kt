package com.powerchina.zhixun.physicalkey

import android.app.Activity
import android.view.KeyEvent
import android.view.Window

/**
 * 在 Window 层拦截按键，优先于 Activity 默认分发。
 */
class PhysicalKeyWindowCallback(
    private val activity: Activity,
    private val delegate: Window.Callback,
) : Window.Callback by delegate {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (PhysicalKeyInterceptor.dispatchKeyEvent(activity, event)) {
            return true
        }
        return delegate.dispatchKeyEvent(event)
    }

    companion object {
        fun install(activity: Activity) {
            val window = activity.window
            val current = window.callback
            if (current is PhysicalKeyWindowCallback) return
            window.callback = PhysicalKeyWindowCallback(activity, current)
        }
    }
}
