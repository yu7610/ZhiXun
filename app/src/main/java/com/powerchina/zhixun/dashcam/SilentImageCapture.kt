package com.powerchina.zhixun.dashcam

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.ImageCapture

/** 尽量静音拍照：关闭快门提示音。 */
object SilentImageCapture {

    private val MUTE_STREAMS = intArrayOf(
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_NOTIFICATION,
    )

    fun build(): ImageCapture {
        val builder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
        val ext = Camera2Interop.Extender(builder)
        ext.setCaptureRequestOption(
            CaptureRequest.CONTROL_ENABLE_ZSL,
            false,
        )
        return builder.build()
    }

    class CaptureSilencer(private val audioManager: AudioManager) {
        private val savedVolumes = IntArray(MUTE_STREAMS.size)

        fun mute() {
            MUTE_STREAMS.forEachIndexed { index, stream ->
                savedVolumes[index] = audioManager.getStreamVolume(stream)
                audioManager.setStreamVolume(stream, 0, 0)
            }
        }

        fun restore() {
            MUTE_STREAMS.forEachIndexed { index, stream ->
                audioManager.setStreamVolume(stream, savedVolumes[index], 0)
            }
        }
    }

    fun muteForCapture(context: Context): CaptureSilencer {
        val silencer = CaptureSilencer(context.getSystemService(AudioManager::class.java))
        silencer.mute()
        return silencer
    }
}
