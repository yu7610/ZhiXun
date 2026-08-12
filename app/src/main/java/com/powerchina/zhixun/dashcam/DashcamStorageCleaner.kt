package com.powerchina.zhixun.dashcam

import android.content.ContentUris
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * 执法仪本地录像空间管理：
 * 存储已用 ≥ [USED_RATIO_TRIGGER] 时，按修改时间从旧到新删除录像，直到低于 [USED_RATIO_TARGET]。
 */
object DashcamStorageCleaner {
    private const val TAG = "DashcamStore"
    /** 已用空间达到该比例触发清理 */
    private const val USED_RATIO_TRIGGER = 0.80
    /** 清理目标：已用降到该比例以下 */
    private const val USED_RATIO_TARGET = 0.75

    /**
     * @return 删除的文件数量
     */
    fun pruneIfStorageHigh(context: Context): Int {
        val root = storageRoot()
        val before = usedRatio(root)
        if (before < USED_RATIO_TRIGGER) {
            Log.d(TAG, "存储占用 ${(before * 100).toInt()}% < ${(USED_RATIO_TRIGGER * 100).toInt()}%，无需清理")
            return 0
        }
        Log.i(
            TAG,
            "存储占用 ${(before * 100).toInt()}% ≥ ${(USED_RATIO_TRIGGER * 100).toInt()}%，开始删除旧录像 root=${root.absolutePath}",
        )
        val candidates = collectDeletableVideos(context).sortedBy { it.lastModifiedMs }
        if (candidates.isEmpty()) {
            Log.w(TAG, "无可用旧录像可删，占用仍 ${(before * 100).toInt()}%")
            return 0
        }
        var deleted = 0
        for (item in candidates) {
            if (usedRatio(root) < USED_RATIO_TARGET) break
            if (deleteVideoItem(context, item)) {
                deleted++
                Log.i(TAG, "已删旧录像 name=${item.displayName} size=${item.sizeBytes}B")
            }
        }
        // 顺带清理空的 originals 残留
        pruneEmptyOrphans(context)
        val after = usedRatio(root)
        Log.i(
            TAG,
            "存储清理完成 deleted=$deleted 占用 ${(before * 100).toInt()}% → ${(after * 100).toInt()}%",
        )
        return deleted
    }

    private fun storageRoot(): File {
        val sdcard0 = File("/storage/sdcard0")
        if (sdcard0.exists()) return sdcard0
        return Environment.getExternalStorageDirectory()
    }

    private fun usedRatio(root: File): Double {
        return runCatching {
            val stat = StatFs(root.absolutePath)
            val total = stat.totalBytes.toDouble()
            if (total <= 0) return@runCatching 0.0
            1.0 - (stat.availableBytes.toDouble() / total)
        }.getOrDefault(0.0)
    }

    private data class VideoItem(
        val file: File?,
        val uri: android.net.Uri?,
        val displayName: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
    )

    private fun collectDeletableVideos(context: Context): List<VideoItem> {
        val items = linkedMapOf<String, VideoItem>()
        fun keyOf(name: String, path: String?) = path?.ifBlank { null } ?: name

        // MediaStore 100MEDIA
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
        )
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("%100MEDIA%"),
            "${MediaStore.Video.Media.DATE_MODIFIED} ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                if (!name.endsWith(".mp4", ignoreCase = true)) continue
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                val file = DashcamRecordingStore.resolveVideoFile(
                    context,
                    DashcamVideoOutput(uri, name),
                )
                items[keyOf(name, file?.absolutePath)] = VideoItem(
                    file = file,
                    uri = uri,
                    displayName = name,
                    sizeBytes = cursor.getLong(sizeCol),
                    lastModifiedMs = cursor.getLong(modCol) * 1000L,
                )
            }
        }

        // sdcard0 / app originals 目录
        listOf(
            DashcamRecordingStore.sdcard0VideoDir(),
            DashcamRecordingStore.publicVideoStorageDir(),
            DashcamRecordingStore.originalsDir(context),
            DashcamRecordingStore.recordingsDir(context),
        ).forEach { dir ->
            dir.listFiles { f -> f.isFile && f.extension.equals("mp4", ignoreCase = true) }
                ?.forEach { file ->
                    val k = keyOf(file.name, file.absolutePath)
                    if (!items.containsKey(k)) {
                        items[k] = VideoItem(
                            file = file,
                            uri = null,
                            displayName = file.name,
                            sizeBytes = file.length(),
                            lastModifiedMs = file.lastModified(),
                        )
                    }
                }
        }
        return items.values.toList()
    }

    private fun deleteVideoItem(context: Context, item: VideoItem): Boolean {
        var ok = false
        item.uri?.let { uri ->
            ok = runCatching {
                context.contentResolver.delete(uri, null, null) > 0
            }.getOrDefault(false) || ok
        }
        item.file?.let { file ->
            if (file.exists()) {
                ok = file.delete() || ok
                // 同名 -00N / -001 都尽量清
                val parent = file.parentFile
                if (parent != null) {
                    val stem = file.nameWithoutExtension.substringBeforeLast('-', "")
                    if (stem.isNotBlank()) {
                        parent.listFiles { f ->
                            f.isFile && f.name.startsWith(stem) &&
                                f.extension.equals("mp4", ignoreCase = true)
                        }?.forEach { sibling ->
                            if (sibling != file) runCatching { sibling.delete() }
                        }
                    }
                }
            }
        }
        return ok
    }

    private fun pruneEmptyOrphans(context: Context) {
        DashcamRecordingStore.originalsDir(context)
            .listFiles { f -> f.isFile && f.length() == 0L }
            ?.forEach { runCatching { it.delete() } }
    }
}
