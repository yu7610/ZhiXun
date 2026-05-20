package com.powerchina.zhixun.dashcam

import android.content.Context
import android.view.KeyEvent
import com.powerchina.zhixun.physicalkey.PhysicalKeyInterceptor

/**
 * Activity 层按键入口（委托给 [PhysicalKeyInterceptor]）。
 */
object VideoKeyActivityHelper {

    fun dispatchKeyEvent(context: Context, event: KeyEvent): Boolean {
        return PhysicalKeyInterceptor.dispatchKeyEvent(context, event)
    }
}
