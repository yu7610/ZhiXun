package com.powerchina.zhixun.audio

import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 为 AudioRecord 启用系统 AEC/NS（设备支持时）。
 */
class AudioRecordEffects private constructor(
    private val acousticEchoCanceler: AcousticEchoCanceler?,
    private val noiseSuppressor: NoiseSuppressor?,
) {
    val aecEnabled: Boolean get() = acousticEchoCanceler?.enabled == true
    val nsEnabled: Boolean get() = noiseSuppressor?.enabled == true

    /**
     * 释放 AEC/NS。须在 [AudioRecord.release] 之前调用；
     * [android.media.audiofx.AudioEffect.release] 的 native 实现可能长时间阻塞，调用方勿在主线程执行。
     */
    fun release() {
        try {
            acousticEchoCanceler?.release()
        } catch (_: Exception) {
        }
        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }
    }

    companion object {
        /** 串行化释放，避免多路 AudioEffect/AudioRecord 并发 teardown 卡死 HAL */
        private val releaseScope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

        /**
         * 在后台按正确顺序释放效果器与 [AudioRecord]。
         * 调用方须先清空本地引用再调用，避免与新建采集竞态。
         */
        fun releaseAsync(effects: AudioRecordEffects?, record: AudioRecord?) {
            if (effects == null && record == null) return
            releaseScope.launch {
                try {
                    effects?.release()
                } catch (_: Exception) {
                }
                try {
                    record?.stop()
                } catch (_: Exception) {
                }
                try {
                    record?.release()
                } catch (_: Exception) {
                }
            }
        }

        fun attach(record: AudioRecord, logTag: String): AudioRecordEffects {
            var aec: AcousticEchoCanceler? = null
            var ns: NoiseSuppressor? = null
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(record.audioSessionId)
                    aec?.enabled = true
                    Log.d(logTag, "AEC已启用 session=${record.audioSessionId}")
                } else {
                    Log.w(logTag, "设备不支持AEC")
                }
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(record.audioSessionId)
                    ns?.enabled = true
                    Log.d(logTag, "NS已启用 session=${record.audioSessionId}")
                } else {
                    Log.w(logTag, "设备不支持NS")
                }
            } catch (e: Exception) {
                Log.e(logTag, "设置音频效果失败", e)
                try {
                    aec?.release()
                } catch (_: Exception) {
                }
                try {
                    ns?.release()
                } catch (_: Exception) {
                }
                aec = null
                ns = null
            }
            return AudioRecordEffects(aec, ns)
        }
    }
}
