package com.powerchina.zhixun.dashcam

/**
 * 执法仪页是否仍持有会话。
 *
 * 息屏不会清掉 active（保证录像继续）；仅离开页面 / Activity destroy 时置 false，
 * 并触发 [onBackground] 停录、释放相机。
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
