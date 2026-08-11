package com.powerchina.zhixun.xiaozhi

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.google.gson.JsonObject
import com.powerchina.zhixun.physicalkey.PhysicalKeyLifecycle
import com.powerchina.zhixun.util.ScreenOnHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * MCP 设备控制：音量 / 屏幕亮度（0–100，对齐小智固件 GetDeviceStatusJson）。
 *
 * 亮度优先改前台 Activity 窗口亮度（无需特殊权限即可立即生效）；
 * 若已授权 WRITE_SETTINGS，再同步系统亮度并关闭自动亮度。
 */
object DeviceControlHelper {

    private const val TAG = "XiaozhiMcp"
    private const val PREFS = "zhixun_device_control"
    private const val KEY_BRIGHTNESS = "screen_brightness_0_100"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun getDeviceStatusJson(context: Context): String {
        val root = JsonObject()
        root.add(
            "audio_speaker",
            JsonObject().apply { addProperty("volume", getVolumePercent(context)) },
        )
        root.add(
            "screen",
            JsonObject().apply { addProperty("brightness", getBrightnessPercent(context)) },
        )
        return root.toString()
    }

    fun getVolumePercent(context: Context): Int {
        val am = context.getSystemService(AudioManager::class.java) ?: return 0
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
        return ((cur * 100f) / max).roundToInt().coerceIn(0, 100)
    }

    fun setVolumePercent(context: Context, volume: Int): Boolean {
        val pct = volume.coerceIn(0, 100)
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = ((pct / 100f) * max).roundToInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        Log.i(TAG, "set_volume pct=$pct stream=$target/$max")
        return true
    }

    fun getBrightnessPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_BRIGHTNESS)) {
            return prefs.getInt(KEY_BRIGHTNESS, 50).coerceIn(0, 100)
        }
        val user = ScreenOnHelper.currentUserBrightnessOrNone()
        if (user >= 0f) {
            return (user * 100f).roundToInt().coerceIn(0, 100)
        }
        return runCatching {
            val raw = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
            )
            ((raw.coerceIn(0, 255) * 100f) / 255f).roundToInt().coerceIn(0, 100)
        }.getOrDefault(50)
    }

    fun setBrightnessPercent(context: Context, brightness: Int): Boolean {
        val pct = brightness.coerceIn(0, 100)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BRIGHTNESS, pct)
            .apply()

        val windowOk = applyWindowBrightness(pct)
        val systemOk = applySystemBrightness(context, pct)
        Log.i(TAG, "set_brightness pct=$pct windowOk=$windowOk systemOk=$systemOk")
        return windowOk || systemOk
    }

    /** Activity 恢复前台时，把已记住的亮度重新应用到窗口。 */
    fun reapplyPersistedBrightness(activity: android.app.Activity) {
        if (activity.isFinishing) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_BRIGHTNESS)) return
        val pct = prefs.getInt(KEY_BRIGHTNESS, 50).coerceIn(0, 100)
        val brightness01 = if (pct <= 0) 0.01f else (pct / 100f).coerceIn(0.01f, 1f)
        ScreenOnHelper.applyUserBrightness(activity, brightness01)
    }

    /** 通过当前前台窗口立即调亮度（主线程）。 */
    private fun applyWindowBrightness(percent: Int): Boolean {
        val brightness01 = if (percent <= 0) 0.01f else (percent / 100f).coerceIn(0.01f, 1f)
        val activity = PhysicalKeyLifecycle.resumedActivity
            ?: ScreenOnHelper.attachedActivityOrNull()
        if (activity == null || activity.isFinishing) {
            Log.w(TAG, "无前台 Activity，窗口亮度暂无法生效 pct=$percent")
            return false
        }
        return runOnMainSync {
            ScreenOnHelper.applyUserBrightness(activity, brightness01)
            true
        }
    }

    /** 有 WRITE_SETTINGS 时同步系统亮度，并关闭自动亮度。 */
    private fun applySystemBrightness(context: Context, percent: Int): Boolean {
        if (!canWriteSettings(context)) {
            Log.d(TAG, "无 WRITE_SETTINGS，跳过系统亮度")
            return false
        }
        val systemValue = ((percent / 100f) * 255f).roundToInt().coerceIn(1, 255)
        return runCatching {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                systemValue,
            )
            Log.i(TAG, "系统亮度已设 pct=$percent system=$systemValue")
            true
        }.onFailure { e ->
            Log.w(TAG, "写系统亮度失败", e)
        }.getOrDefault(false)
    }

    private fun <T> runOnMainSync(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        val latch = CountDownLatch(1)
        @Suppress("UNCHECKED_CAST")
        var result: T? = null
        var error: Throwable? = null
        mainHandler.post {
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    fun canWriteSettings(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else {
            true
        }

    fun openWriteSettingsPage(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
