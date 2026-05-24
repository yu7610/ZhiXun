package com.powerchina.zhixun.dashcam

import android.view.KeyEvent

/**
 * 执法仪物理键 keyCode 映射。
 */
object PhysicalKeyCodes {
    /** 拍照键（KEYCODE_F12） */
    const val PHOTO = KeyEvent.KEYCODE_F12 // 142

    /** 录像键 */
    const val RECORD = 136

    fun actionForKeyCode(keyCode: Int): DashcamVideoKeyEvents.KeyAction? {
        return when (keyCode) {
            PHOTO -> DashcamVideoKeyEvents.KeyAction.PHOTO
            RECORD -> DashcamVideoKeyEvents.KeyAction.RECORD
            else -> null
        }
    }

    fun isMappedKey(keyCode: Int): Boolean = actionForKeyCode(keyCode) != null
}
