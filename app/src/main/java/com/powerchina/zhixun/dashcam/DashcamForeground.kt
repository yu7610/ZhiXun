package com.powerchina.zhixun.dashcam

/**
 * 执法仪页是否在前台；后台时不应占用相机供 MCP 拍照使用。
 */
object DashcamForeground {

    @Volatile
    var isActive: Boolean = false
        private set

    @Volatile
    var onBackground: (() -> Unit)? = null

    fun setActive(active: Boolean) {
        if (isActive == active) return
        isActive = active
        if (!active) {
            onBackground?.invoke()
        }
    }
}
