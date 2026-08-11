package com.powerchina.zhixun.dashcam

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

data class PendingVideoUpload(
    val id: String = UUID.randomUUID().toString(),
    val filePath: String,
    val terCode: String,
    val durationSec: Int,
    val recordTimeMs: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
)

/**
 * 待上传录像片段持久化队列（JSON 文件）。
 * 无网时入队，有网后由 [VideoUploadCoordinator] 消费。
 */
object PendingVideoUploadStore {

    private const val TAG = DashcamVideoClipUploader.TAG
    private const val FILE_NAME = "pending_video_uploads.json"
    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<PendingVideoUpload>>() {}.type
    private val lock = Any()

    fun enqueue(context: Context, item: PendingVideoUpload) {
        synchronized(lock) {
            val list = loadLocked(context)
            // 同路径去重，保留最新元数据
            list.removeAll { it.filePath == item.filePath }
            list.add(item)
            saveLocked(context, list)
            Log.i(TAG, "入队待上传 id=${item.id} file=${item.filePath} queue=${list.size}")
        }
    }

    fun list(context: Context): List<PendingVideoUpload> = synchronized(lock) {
        loadLocked(context).toList()
    }

    fun remove(context: Context, id: String) {
        synchronized(lock) {
            val list = loadLocked(context)
            if (list.removeAll { it.id == id }) {
                saveLocked(context, list)
                Log.i(TAG, "出队 id=$id remain=${list.size}")
            }
        }
    }

    fun bumpAttempt(context: Context, id: String): PendingVideoUpload? = synchronized(lock) {
        val list = loadLocked(context)
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val updated = list[idx].copy(attempts = list[idx].attempts + 1)
        list[idx] = updated
        saveLocked(context, list)
        updated
    }

    private fun storeFile(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    private fun loadLocked(context: Context): MutableList<PendingVideoUpload> {
        val file = storeFile(context)
        if (!file.exists() || file.length() == 0L) return mutableListOf()
        return runCatching {
            gson.fromJson<MutableList<PendingVideoUpload>>(file.readText(), listType)
                ?: mutableListOf()
        }.getOrElse {
            Log.w(TAG, "读取待上传队列失败，重置", it)
            mutableListOf()
        }
    }

    private fun saveLocked(context: Context, list: MutableList<PendingVideoUpload>) {
        storeFile(context).writeText(gson.toJson(list))
    }
}
