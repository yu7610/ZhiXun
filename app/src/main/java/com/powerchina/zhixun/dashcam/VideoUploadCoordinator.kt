package com.powerchina.zhixun.dashcam

import android.content.Context
import android.location.LocationManager
import android.util.Log
import com.powerchina.zhixun.data.ConfigManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 录像片段上传协调：
 * - 有网：立即 uploadVideo，并尝试 RTSP 文件推流
 * - 无网：本地已保存的文件入队，网络恢复后自动补传
 */
object VideoUploadCoordinator {

    private const val TAG = DashcamVideoClipUploader.TAG
    private const val MAX_ATTEMPTS = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drainMutex = Mutex()
    private val draining = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        NetworkMonitor.ensureRegistered(app)
        NetworkMonitor.addListener(
            NetworkMonitor.Listener {
                drainQueue(app)
            },
        )
        // 启动时尝试清空积压
        drainQueue(app)
    }

    /**
     * 录像压缩完成后调用：本地文件已就绪。
     * @return 用户可读状态文案
     */
    suspend fun onLocalVideoReady(
        context: Context,
        videoFile: File,
        durationSec: Int,
        recordTimeMs: Long = videoFile.lastModified().takeIf { it > 0L }
            ?: System.currentTimeMillis(),
    ): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        appContext = app
        if (!videoFile.exists() || videoFile.length() == 0L) {
            return@withContext "本地录像无效"
        }
        val terCode = ConfigManager(app).loadConfig().macAddress
        val (lat, lng) = lastKnownLatLng(app)
        val pending = PendingVideoUpload(
            filePath = videoFile.absolutePath,
            terCode = terCode,
            durationSec = durationSec.coerceAtLeast(1),
            recordTimeMs = recordTimeMs,
            latitude = lat,
            longitude = lng,
        )

        if (!NetworkMonitor.isOnline(app)) {
            PendingVideoUploadStore.enqueue(app, pending)
            Log.i(TAG, "无网络，已本地保存并入队: ${videoFile.name}")
            return@withContext "已保存本地，待联网上传"
        }

        val upload = DashcamVideoClipUploader.upload(
            context = app,
            videoFile = videoFile,
            terCode = terCode,
            durationSec = pending.durationSec,
            recordTimeMs = recordTimeMs,
            latitude = lat,
            longitude = lng,
        )
        if (upload.isSuccess) {
            // 录制中已实时 RTSP；此处不再做文件回放推流，避免重复占流
            Log.i(TAG, "上传成功: ${videoFile.name}")
            "录像已上传"
        } else {
            PendingVideoUploadStore.enqueue(app, pending)
            Log.w(TAG, "上传失败已入队: ${upload.exceptionOrNull()?.message}")
            "上传失败，已加入待传队列"
        }
    }

    fun drainQueue(context: Context? = null) {
        val app = context?.applicationContext ?: appContext ?: return
        if (!NetworkMonitor.isOnline(app)) return
        if (!draining.compareAndSet(false, true)) return
        scope.launch {
            try {
                drainMutex.withLock {
                    val items = PendingVideoUploadStore.list(app)
                    if (items.isEmpty()) return@withLock
                    Log.i(TAG, "开始补传队列 size=${items.size}")
                    for (item in items) {
                        if (!NetworkMonitor.isOnline(app)) break
                        val file = File(item.filePath)
                        if (!file.exists() || file.length() == 0L) {
                            Log.w(TAG, "文件已丢失，移除队列 id=${item.id}")
                            PendingVideoUploadStore.remove(app, item.id)
                            continue
                        }
                        if (item.attempts >= MAX_ATTEMPTS) {
                            Log.w(TAG, "超过重试次数，保留本地文件 id=${item.id}")
                            continue
                        }
                        PendingVideoUploadStore.bumpAttempt(app, item.id)
                        val result = DashcamVideoClipUploader.upload(
                            context = app,
                            videoFile = file,
                            terCode = item.terCode,
                            durationSec = item.durationSec,
                            recordTimeMs = item.recordTimeMs,
                            latitude = item.latitude,
                            longitude = item.longitude,
                        )
                        if (result.isSuccess) {
                            PendingVideoUploadStore.remove(app, item.id)
                        } else {
                            Log.w(TAG, "补传失败 id=${item.id}: ${result.exceptionOrNull()?.message}")
                        }
                    }
                }
            } finally {
                draining.set(false)
            }
        }
    }

    @Suppress("MissingPermission")
    private fun lastKnownLatLng(context: Context): Pair<Double?, Double?> {
        return runCatching {
            val lm = context.getSystemService(LocationManager::class.java) ?: return null to null
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )
            val best = providers
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
            best?.let { it.latitude to it.longitude } ?: (null to null)
        }.getOrDefault(null to null)
    }
}
