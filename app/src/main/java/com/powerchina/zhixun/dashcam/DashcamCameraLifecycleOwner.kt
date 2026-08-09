package com.powerchina.zhixun.dashcam

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * 执法仪相机绑定用生命周期：不跟随 Activity 息屏 ON_STOP，
 * 以便熄屏后仍保持 CameraX 用例（录像）运行。
 *
 * 仅在离开执法仪页（Composable dispose）时 [destroy]。
 */
class DashcamCameraLifecycleOwner : LifecycleOwner {

    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = registry

    fun start() {
        if (registry.currentState == Lifecycle.State.INITIALIZED) {
            registry.currentState = Lifecycle.State.CREATED
        }
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED) &&
            registry.currentState != Lifecycle.State.DESTROYED
        ) {
            registry.currentState = Lifecycle.State.STARTED
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.INITIALIZED ||
            registry.currentState == Lifecycle.State.DESTROYED
        ) {
            return
        }
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
