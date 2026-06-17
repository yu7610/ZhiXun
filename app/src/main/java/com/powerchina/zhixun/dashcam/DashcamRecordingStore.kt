package com.powerchina.zhixun.dashcam

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DashcamRecordingStore {
    private const val TAG = "DashcamStore"
    private const val SUB_DIR = "dashcam"
    /** MediaStore RELATIVE_PATH → /storage/emulated/0/DCIM/100MEDIA（多数设备即 sdcard0） */
    const val PUBLIC_VIDEO_RELATIVE_PATH = "DCIM/100MEDIA/"
    /** 本机系统「视频」应用只展示此分组（bucket_display_name=ZhiXun） */
    const val ZHIXUN_VIDEO_RELATIVE_PATH = "DCIM/ZhiXun/"
    /** 执法仪系统「视频」页扫描外置 TF 卡此目录，文件名 yyyyMMddHHmmss-00N.MP4 */
    const val OEM_TF_MP4_RELATIVE_PATH = "DCIM/100MEDIA/MP4"
    const val OEM_MP4_SUFFIX = "-00N.MP4"
    private val OEM_STEM_PATTERN = Regex("^(\\d{14})")
    private val OEM_VIDEO_NAME_PATTERN =
        Regex("^\\d{14}-(00N|\\d{3})\\.MP4$", RegexOption.IGNORE_CASE)

    fun createOemVideoFileName(date: Date = Date()): String {
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(date)
        return buildOemMp4FileName(stamp)
    }

    fun isOemVideoFileName(name: String): Boolean = OEM_VIDEO_NAME_PATTERN.matches(name)

    fun fileProviderAuthority(context: Context): String =
        "${context.packageName}.fileprovider"

    fun uriForFile(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, fileProviderAuthority(context), file)

    fun uriForClip(context: Context, clip: DashcamClip): Uri =
        clip.uri ?: uriForFile(context, clip.file)

    fun recordingsDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        val dir = File(base, SUB_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun originalsDir(context: Context): File {
        val dir = File(recordingsDir(context), "originals")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 同卷时压缩片使用 -001.MP4，避免覆盖原片 -00N.MP4 */
    fun compressedSdcard0Name(displayName: String, sameVolumeAlias: Boolean): String =
        if (sameVolumeAlias) {
            buildOemCompressedFileName(parseOemMp4Stem(displayName))
        } else {
            displayName
        }

    fun buildOemCompressedFileName(stem: String): String =
        String.format(Locale.US, "%s-001.MP4", stem)

    fun isSameVolumeAlias(emulated: File, sdcard0: File): Boolean =
        runCatching { emulated.canonicalFile == sdcard0.canonicalFile }.getOrDefault(false)

    /** 用于容量展示：对应公共 DCIM/100MEDIA 所在卷 */
    fun publicVideoStorageDir(): File =
        File(Environment.getExternalStorageDirectory(), PUBLIC_VIDEO_RELATIVE_PATH)

    fun isPublicVideoMediaFile(file: File): Boolean {
        val path = file.absolutePath.replace('\\', '/')
        return path.contains("/DCIM/100MEDIA/")
    }

    fun videoCollectionUri(): Uri =
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun createVideoOutputRequest(context: Context): DashcamVideoOutputRequest {
        val name = createOemVideoFileName()
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, PUBLIC_VIDEO_RELATIVE_PATH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        Log.i(
            TAG,
            "准备录像 collection=${videoCollectionUri()} name=$name path=$PUBLIC_VIDEO_RELATIVE_PATH",
        )
        return DashcamVideoOutputRequest(displayName = name, contentValues = values)
    }

    fun publishVideoOutput(context: Context, output: DashcamVideoOutput): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            val updated = context.contentResolver.update(output.uri, values, null, null)
            Log.i(TAG, "IS_PENDING→0 updateRows=$updated uri=${output.uri}")
        }
        val ready = waitForVideoReady(context, output)
        ready.onFailure { err -> Log.w(TAG, "录像落盘等待失败 ${output.displayName}", err) }
        resolveVideoFile(context, output)?.let { file ->
            indexVideoInGallery(context, file, output.uri)
        }
        return buildVideoPublishReport(context, output, readyBytes = ready.getOrNull())
    }

    /**
     * Finalize 后 MediaStore SIZE 可能已更新，但文件字节尚未刷盘。
     * 过早读 uri / 写 sdcard0 路径会把同卷文件截断为 0 字节。
     */
    fun waitForVideoReady(
        context: Context,
        output: DashcamVideoOutput,
        timeoutMs: Long = 10_000L,
        pollMs: Long = 200L,
    ): Result<Long> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var prevLen = -1L
        var stableRounds = 0
        while (System.currentTimeMillis() < deadline) {
            val info = queryVideoOutputInfo(context, output.uri)
            val file = resolveVideoFile(context, output)
            val fileLen = file?.length() ?: 0L
            if (fileLen > 0L && fileLen == prevLen) {
                stableRounds++
            } else {
                stableRounds = 0
            }
            prevLen = fileLen
            val expected = info.sizeBytes
            val sizeOk = expected <= 0L || fileLen >= expected
            if (fileLen > 0L && stableRounds >= 1 && sizeOk) {
                Log.i(
                    TAG,
                    "录像已落盘 ${output.displayName} fileLen=$fileLen expected=$expected " +
                        "path=${file?.absolutePath}",
                )
                return Result.success(fileLen)
            }
            Log.d(
                TAG,
                "等待录像落盘 ${output.displayName} fileLen=$fileLen expected=$expected " +
                    "stable=$stableRounds",
            )
            Thread.sleep(pollMs)
        }
        val fileLen = resolveVideoFile(context, output)?.length() ?: 0L
        return Result.failure(
            IllegalStateException(
                "录像未落盘 timeout=${timeoutMs}ms fileLen=$fileLen uri=${output.uri}",
            ),
        )
    }

    fun sdcard0VideoDir(): File = File("/storage/sdcard0/$PUBLIC_VIDEO_RELATIVE_PATH")

    fun zhiXunVideoDir(): File =
        File(Environment.getExternalStorageDirectory(), "DCIM/ZhiXun")

    fun buildVideoPublishReport(
        context: Context,
        output: DashcamVideoOutput,
        readyBytes: Long? = null,
    ): String {
        val info = queryVideoOutputInfo(context, output.uri)
        val resolved = resolveVideoFile(context, output)
        val emulated = File(publicVideoStorageDir(), output.displayName)
        val fileLen = resolved?.length() ?: readyBytes ?: 0L
        val report = buildString {
            append("uri=").append(output.uri)
            append(" size=").append(info.sizeBytes)
            append(" fileLen=").append(fileLen)
            append(" pending=").append(info.isPending)
            append(" rel=").append(info.relativePath ?: "null")
            append(" path=").append(resolved?.absolutePath ?: "null")
            append(" pathExists=").append(resolved?.exists() == true)
            append(" emulated=").append(emulated.absolutePath)
            append(" emulatedExists=").append(emulated.exists())
            append(" sdcard0=压缩后写入")
        }
        Log.i(TAG, "录像发布报告 ${output.displayName}: $report")
        return report
    }

    data class Sdcard0SaveResult(
        val file: File,
        val sameVolumeAlias: Boolean,
        val emulatedFile: File,
    )

    /**
     * 压缩片写入 sdcard0/DCIM/100MEDIA。
     * 同卷时先写 -001.MP4，校验通过后 shell cp 覆盖原片 -00N.MP4，再删 -001。
     */
    fun saveCompressedVideoToSdcard0(
        context: Context,
        compressedTemp: File,
        displayName: String,
        originalRecordingUri: Uri,
        originalSourceFile: File,
    ): Sdcard0SaveResult? {
        if (!compressedTemp.exists() || compressedTemp.length() == 0L) {
            Log.w(TAG, "压缩片为空，跳过 sdcard0: ${compressedTemp.absolutePath}")
            return null
        }
        if (!originalSourceFile.exists() || originalSourceFile.length() == 0L) {
            Log.w(TAG, "原片不存在，跳过 sdcard0: ${originalSourceFile.absolutePath}")
            return null
        }
        val sourceBytes = compressedTemp.length()
        val originalBytes = originalSourceFile.length()
        deleteStaleDuplicateVideos(context, displayName, originalRecordingUri)
        backupOriginalVideo(context, originalSourceFile)

        val emulatedFile = originalSourceFile
        val sdcard0Rec = File(sdcard0VideoDir(), displayName)
        val sameAlias = isSameVolumeAlias(emulatedFile, sdcard0Rec)
        val sdcard0Name = compressedSdcard0Name(displayName, sameAlias)
        val dest = File(sdcard0VideoDir(), sdcard0Name)

        if (sameAlias) {
            Log.i(
                TAG,
                "emulated 与 sdcard0 同卷，原片保留 ${emulatedFile.absolutePath}，" +
                    "压缩片→${dest.absolutePath}",
            )
        }

        val written = tryShellCopyToSdcard0(compressedTemp, dest, sourceBytes)
            ?: writeCompressedToSdcard0Path(compressedTemp, dest, sourceBytes)
            ?: writeCompressedViaMediaStoreSdcard0(
                context = context,
                compressedTemp = compressedTemp,
                displayName = sdcard0Name,
                originalRecordingUri = originalRecordingUri,
                sourceBytes = sourceBytes,
            )

        if (written == null || fileSizeOnDevice(written) <= 0L) {
            Log.e(TAG, "压缩片写入 sdcard0 失败 name=$sdcard0Name dest=${dest.absolutePath}")
            return null
        }

        if (emulatedFile.length() != originalBytes) {
            Log.w(
                TAG,
                "原片大小变化，尝试恢复 emulated=${emulatedFile.absolutePath} " +
                    "was=$originalBytes now=${emulatedFile.length()}",
            )
            restoreOriginalFromBackup(context, displayName, emulatedFile, originalBytes)
        }

        val finalFile = finalizeCompressedVideo(
            context = context,
            originalDisplayName = displayName,
            originalRecordingUri = originalRecordingUri,
            originalSourceFile = emulatedFile,
            compressedFile = written,
            sameVolumeAlias = sameAlias,
            originalBytes = originalBytes,
        )
        if (finalFile == null) {
            Log.e(TAG, "压缩收尾失败，已尝试恢复原片 name=$displayName")
            return null
        }
        Log.i(
            TAG,
            "压缩完成 final=${finalFile.absolutePath} size=${finalFile.length()}",
        )
        return Sdcard0SaveResult(
            file = finalFile,
            sameVolumeAlias = sameAlias,
            emulatedFile = finalFile,
        )
    }

    /**
     * 压缩校验通过后替换原片；更新原 MediaStore 条目（删 uri 会连带删文件）。
     */
    private fun finalizeCompressedVideo(
        context: Context,
        originalDisplayName: String,
        originalRecordingUri: Uri,
        originalSourceFile: File,
        compressedFile: File,
        sameVolumeAlias: Boolean,
        originalBytes: Long,
    ): File? {
        val compressedBytes = fileSizeOnDevice(compressedFile)
        if (compressedBytes <= 0L) {
            Log.e(TAG, "压缩片无效: ${compressedFile.absolutePath}")
            return null
        }

        val oemName = buildOemMp4FileName(parseOemMp4Stem(originalDisplayName))
        val finalFile = if (sameVolumeAlias && !compressedFile.name.equals(oemName, ignoreCase = true)) {
            replaceOriginalWithCompressed(
                originalSourceFile = originalSourceFile,
                compressedFile = compressedFile,
                oemName = oemName,
                expectedBytes = compressedBytes,
            )
        } else {
            if (!shellRm(originalSourceFile)) {
                Log.w(TAG, "删除原片失败，保留 ${originalSourceFile.name}")
                return null
            }
            compressedFile.takeIf { fileSizeOnDevice(it) == compressedBytes }
        }

        val finalBytes = finalFile?.let { fileSizeOnDevice(it) } ?: 0L
        if (finalFile == null || finalBytes != compressedBytes) {
            Log.e(
                TAG,
                "压缩片落盘校验失败 expected=$compressedBytes actual=$finalBytes",
            )
            restoreOriginalFromBackup(context, originalDisplayName, originalSourceFile, originalBytes)
            shellRm(compressedFile)
            return null
        }

        File(originalsDir(context), originalDisplayName).delete()
        if (sameVolumeAlias) {
            val tempName = buildOemCompressedFileName(parseOemMp4Stem(originalDisplayName))
            findVideoUriByDisplayName(context, tempName)?.let { deleteVideoIndex(context, it) }
        }
        deleteStaleDuplicateVideos(context, oemName, originalRecordingUri)
        indexVideoInGallery(context, finalFile, originalRecordingUri)
        Log.i(TAG, "压缩成功，已替换原片 ${finalFile.name} size=$finalBytes")
        return finalFile
    }

    /** 同卷：压缩片校验通过后 shell cp 覆盖原片 -00N.MP4，再删 -001.MP4 */
    private fun replaceOriginalWithCompressed(
        originalSourceFile: File,
        compressedFile: File,
        oemName: String,
        expectedBytes: Long,
    ): File? {
        val dest = File(originalSourceFile.parentFile, oemName)
        if (!shellCopyTo(compressedFile, dest, expectedBytes)) {
            Log.w(TAG, "压缩片覆盖原片失败 ${compressedFile.name} -> ${dest.name}")
            return null
        }
        shellRm(compressedFile)
        val size = fileSizeOnDevice(dest)
        if (size != expectedBytes) {
            Log.w(TAG, "覆盖后大小不符 ${dest.name} size=$size expected=$expectedBytes")
            return null
        }
        Log.i(TAG, "压缩片已覆盖原片 ${compressedFile.name} -> ${dest.name} size=$size")
        return dest
    }

    /** sdcard0 路径 Java File.length() 可能为 0，回退 shell stat */
    fun fileSizeOnDevice(file: File): Long {
        val size = file.length()
        if (size > 0L) return size
        return shellFileSize(file) ?: 0L
    }

    private fun shellFileSize(file: File): Long? =
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("stat", "-c", "%s", file.absolutePath))
            if (process.waitFor() != 0) return@runCatching null
            process.inputStream.bufferedReader().readText().trim().toLongOrNull()
        }.getOrNull()

    private fun deleteVideoIndex(context: Context, uri: Uri) {
        if (uri == Uri.EMPTY) return
        runCatching {
            val deleted = context.contentResolver.delete(uri, null, null)
            Log.i(TAG, "已删除 MediaStore 索引 uri=$uri rows=$deleted")
        }.onFailure { err ->
            Log.w(TAG, "删除 MediaStore 索引失败 uri=$uri", err)
        }
    }

    private fun shellRm(file: File): Boolean =
        runCatching {
            if (!file.exists()) return@runCatching true
            Runtime.getRuntime().exec(arrayOf("rm", "-f", file.absolutePath)).waitFor() == 0
        }.onFailure { err ->
            Log.w(TAG, "shell rm 失败 ${file.absolutePath}", err)
        }.getOrDefault(false)

    private fun shellCopyTo(source: File, dest: File, expectedBytes: Long): Boolean =
        runCatching {
            val process = Runtime.getRuntime().exec(
                arrayOf("cp", source.absolutePath, dest.absolutePath),
            )
            process.waitFor() == 0 && dest.exists() && dest.length() == expectedBytes
        }.onFailure { err ->
            Log.w(TAG, "shell cp 失败 ${source.absolutePath} -> ${dest.absolutePath}", err)
        }.getOrDefault(false)

    private fun shellMove(source: File, dest: File): Boolean =
        runCatching {
            if (!source.exists() || source.length() <= 0L) return@runCatching false
            val process = Runtime.getRuntime().exec(
                arrayOf("mv", source.absolutePath, dest.absolutePath),
            )
            process.waitFor() == 0 && dest.exists() && dest.length() > 0L
        }.onFailure { err ->
            Log.w(TAG, "shell mv 失败 ${source.absolutePath} -> ${dest.absolutePath}", err)
        }.getOrDefault(false)

    private fun backupOriginalVideo(context: Context, source: File): File? {
        val backup = File(originalsDir(context), source.name)
        return runCatching {
            if (backup.exists() && backup.length() == source.length()) {
                Log.d(TAG, "原片备份已存在: ${backup.absolutePath}")
                return backup
            }
            val process = Runtime.getRuntime().exec(
                arrayOf("cp", source.absolutePath, backup.absolutePath),
            )
            if (process.waitFor() != 0 || !backup.exists() || backup.length() != source.length()) {
                throw IllegalStateException("原片备份失败 size=${backup.length()}")
            }
            Log.i(TAG, "原片已备份: ${backup.absolutePath} size=${backup.length()}")
            backup
        }.onFailure { err ->
            Log.w(TAG, "原片备份失败: ${source.absolutePath}", err)
        }.getOrNull()
    }

    private fun restoreOriginalFromBackup(
        context: Context,
        displayName: String,
        emulatedFile: File,
        expectedBytes: Long,
    ) {
        val backup = File(originalsDir(context), displayName)
        if (!backup.exists() || backup.length() != expectedBytes) {
            Log.e(TAG, "无法恢复原片，备份无效 name=$displayName")
            return
        }
        val restored = tryShellCopyToSdcard0(backup, emulatedFile, expectedBytes)
        if (restored != null) {
            indexVideoInGallery(context, emulatedFile, null)
            Log.i(TAG, "已从备份恢复原片 ${emulatedFile.name} size=${emulatedFile.length()}")
        } else {
            Log.e(TAG, "从备份恢复原片失败 ${emulatedFile.absolutePath}")
        }
    }

    @Suppress("DEPRECATION")
    private fun findVideoUriByDisplayName(context: Context, displayName: String): Uri? {
        val collection = videoCollectionUri()
        val projection = arrayOf(MediaStore.Video.Media._ID)
        val selection = "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND " +
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(displayName, "%100MEDIA%")
        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                return ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }

    /** 通过 StorageVolume 定位的 sdcard0 路径写入（避免 MediaStore 生成 (1) 后缀） */
    private fun writeCompressedToSdcard0Path(
        compressedTemp: File,
        dest: File,
        sourceBytes: Long,
    ): File? {
        val destDir = dest.parentFile ?: return null
        if (!destDir.exists() && !destDir.mkdirs()) {
            Log.w(TAG, "无法创建 sdcard0 目录: ${destDir.absolutePath}")
            return null
        }
        val temp = File(destDir, "${dest.name}.copying")
        return runCatching {
            compressedTemp.inputStream().use { input ->
                temp.outputStream().use { out -> input.copyTo(out) }
            }
            if (temp.length() != sourceBytes) {
                temp.delete()
                throw IllegalStateException(
                    "sdcard0 字节不一致 copied=${temp.length()} expected=$sourceBytes",
                )
            }
            if (dest.exists() && !dest.delete()) {
                throw IllegalStateException("无法覆盖 sdcard0 旧文件 ${dest.absolutePath}")
            }
            if (!temp.renameTo(dest)) {
                throw IllegalStateException("无法完成 sdcard0 写入 ${dest.absolutePath}")
            }
            dest
        }.onFailure {
            temp.delete()
        }.getOrNull()
    }

    /** 部分执法仪允许 shell 写入 sdcard0，直写 EPERM 时回退 */
    private fun tryShellCopyToSdcard0(
        source: File,
        dest: File,
        expectedBytes: Long,
    ): File? {
        val destDir = dest.parentFile ?: return null
        if (!destDir.exists()) destDir.mkdirs()
        return runCatching {
            if (!shellCopyTo(source, dest, expectedBytes)) {
                shellRm(dest)
                throw IllegalStateException("shell cp 失败 size=${dest.length()}")
            }
            Log.i(TAG, "shell cp 写入 sdcard0: ${dest.absolutePath} size=${dest.length()}")
            dest
        }.onFailure { err ->
            Log.w(TAG, "shell cp 失败: ${dest.absolutePath}", err)
        }.getOrNull()
    }

    /**
     * 直写失败时，覆盖 DATA 在 sdcard0 下的 MediaStore 条目；
     * 若无独立条目则跳过（避免 insert 产生 REC_xxx (1).mp4 污染 emulated）。
     */
    private fun writeCompressedViaMediaStoreSdcard0(
        context: Context,
        compressedTemp: File,
        displayName: String,
        originalRecordingUri: Uri,
        sourceBytes: Long,
    ): File? {
        val uri = findSdcard0VideoUri(context, displayName)
        if (uri == null || uri == originalRecordingUri) {
            Log.w(
                TAG,
                "无独立 sdcard0 MediaStore 条目，跳过 MediaStore 回退 name=$displayName",
            )
            return null
        }
        return runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                compressedTemp.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("openOutputStream 失败 uri=$uri")
            resolveVideoFile(context, DashcamVideoOutput(uri, displayName))
                ?: File(sdcard0VideoDir(), displayName)
        }.onFailure { err ->
            Log.w(TAG, "MediaStore 回写 sdcard0 失败 uri=$uri", err)
        }.getOrNull()
    }

    fun resolveSdcard0Root(context: Context): File? {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        sm?.storageVolumes?.forEach { volume ->
            val dir = volume.directory ?: return@forEach
            if (dir.absolutePath.contains("sdcard0")) {
                Log.i(TAG, "StorageVolume sdcard0=${dir.absolutePath}")
                return dir
            }
        }
        return File("/storage/sdcard0").takeIf { it.exists() }
    }

    /** 外置 TF 卡 DCIM/100MEDIA/MP4（系统「视频」页数据来源） */
    fun resolveExternalTfMp4Dir(context: Context): File? {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        sm?.storageVolumes?.forEach { volume ->
            if (volume.isRemovable && !volume.isPrimary) {
                val root = volume.directory ?: return@forEach
                val mediaRoot = File(root, "DCIM/100MEDIA")
                if (mediaRoot.exists()) {
                    val mp4Dir = File(root, OEM_TF_MP4_RELATIVE_PATH)
                    if (!mp4Dir.exists() && !mp4Dir.mkdirs()) {
                        Log.w(TAG, "无法创建外置 MP4 目录: ${mp4Dir.absolutePath}")
                        return null
                    }
                    Log.i(TAG, "外置 TF MP4 目录: ${mp4Dir.absolutePath}")
                    return mp4Dir
                }
            }
        }
        File("/storage").listFiles()?.forEach { vol ->
            if (!vol.isDirectory || vol.name in SKIP_STORAGE_VOLUME_NAMES) return@forEach
            val mediaRoot = File(vol, "DCIM/100MEDIA")
            if (!mediaRoot.exists()) return@forEach
            val mp4Dir = File(vol, OEM_TF_MP4_RELATIVE_PATH)
            if (!mp4Dir.exists() && !mp4Dir.mkdirs()) return@forEach
            Log.i(TAG, "外置 TF MP4 目录(扫描): ${mp4Dir.absolutePath}")
            return mp4Dir
        }
        Log.w(TAG, "未找到外置 TF 卡 DCIM/100MEDIA/MP4")
        return null
    }

    private val SKIP_STORAGE_VOLUME_NAMES = setOf(
        "emulated", "self", "sdcard0", "sdcard1",
    )

    private fun stripMp4Extension(name: String): String =
        if (name.endsWith(".mp4", ignoreCase = true)) name.dropLast(4) else name

    /** 20260610000213-00N.MP4 / REC_20260609_234135.mp4 → 20260610000213 */
    fun parseOemMp4Stem(fileName: String): String {
        val base = stripMp4Extension(fileName)
            .removePrefix("REC_")
            .removeSuffix("_480p")
            .removeSuffix("_480P")
        OEM_STEM_PATTERN.find(base)?.groupValues?.get(1)?.let { return it }
        val compact = base.replace("_", "")
        if (compact.length >= 14 && compact.substring(0, 14).all { it.isDigit() }) {
            return compact.substring(0, 14)
        }
        return SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
    }

    fun toOemMp4Stem(source: File): String = parseOemMp4Stem(source.name)

    fun buildOemMp4FileName(stem: String): String = "$stem$OEM_MP4_SUFFIX"

    fun resolveOemMp4Dest(mp4Dir: File, source: File): File {
        val stem = toOemMp4Stem(source)
        val primary = File(mp4Dir, buildOemMp4FileName(stem))
        if (!primary.exists() || primary.length() == source.length()) {
            return primary
        }
        var seq = 1
        while (seq < 1000) {
            val alt = File(mp4Dir, String.format(Locale.US, "%s-%03d.MP4", stem, seq))
            if (!alt.exists() || alt.length() == source.length()) {
                return alt
            }
            seq++
        }
        return primary
    }

    fun oemMapFileFor(mp4Dest: File): File {
        val stem = parseOemMp4Stem(mp4Dest.name)
        return File(mp4Dest.parentFile, "${stem}-00N.MAP")
    }

    /** 执法仪 MAP 侧车：yyyy-MM-dd HH:mm:ss lat lng */
    fun formatOemMapContent(stem: String, latitude: Double = 0.0, longitude: Double = 0.0): String {
        if (stem.length != 14 || !stem.all { it.isDigit() }) {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(Date())
            return "$stamp $latitude $longitude\r\n"
        }
        val formatted = "${stem.substring(0, 4)}-${stem.substring(4, 6)}-${stem.substring(6, 8)} " +
            "${stem.substring(8, 10)}:${stem.substring(10, 12)}:${stem.substring(12, 14)}"
        return "$formatted $latitude $longitude\r\n"
    }

    /** 删除 100MEDIA 下同名重复 MediaStore 条目，保留原片 uri */
    @Suppress("DEPRECATION")
    private fun deleteStaleDuplicateVideos(
        context: Context,
        displayName: String,
        keepUri: Uri,
    ) {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val baseName = stripMp4Extension(displayName)
        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME)
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND " +
            "(${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? OR " +
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?)"
        val args = arrayOf("%100MEDIA%", "$baseName (%", "$baseName(%)")
        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val uri = ContentUris.withAppendedId(collection, id)
                if (uri == keepUri) continue
                context.contentResolver.delete(uri, null, null)
                Log.i(TAG, "已删除重复 MediaStore 视频 name=$name uri=$uri")
            }
        }
    }

    /** 查找 DATA 路径在 /storage/sdcard0/ 下的同名视频 */
    @Suppress("DEPRECATION")
    private fun findSdcard0VideoUri(context: Context, displayName: String): Uri? {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATA)
        val selection = "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND " +
            "${MediaStore.Video.Media.DATA} LIKE ?"
        val args = arrayOf(displayName, "/storage/sdcard0/%")
        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                val data = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA))
                Log.i(TAG, "命中 sdcard0 MediaStore 条目 id=$id data=$data")
                return ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }

    /**
     * 将磁盘上的 mp4 注册进系统媒体库，使「视频/相册」应用可见。
     */
    fun indexVideoInGallery(
        context: Context,
        file: File,
        knownUri: Uri? = null,
        relativePath: String = PUBLIC_VIDEO_RELATIVE_PATH,
    ): Uri? {
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "跳过媒体库索引，文件无效: ${file.absolutePath}")
            return null
        }
        val app = context.applicationContext
        val uri = knownUri
            ?: findVideoUriByFile(app, file)
            ?: insertVideoIndexEntry(app, file, relativePath)

        if (uri != null) {
            updateVideoIndexMetadata(app, uri, file)
        }

        scanVideoFiles(app, file)
        notifyVideoCollectionChanged(app, uri, file)

        Log.i(
            TAG,
            "媒体库已索引 name=${file.name} path=${file.absolutePath} " +
                "size=${file.length()} uri=$uri",
        )
        return uri
    }

    @Suppress("DEPRECATION")
    private fun findVideoUriByFile(context: Context, file: File): Uri? {
        val collection = videoCollectionUri()
        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATA)
        val path = file.absolutePath
        val selection = "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND " +
            "(${MediaStore.Video.Media.DATA} = ? OR ${MediaStore.Video.Media.DATA} LIKE ?)"
        val args = arrayOf(file.name, path, "%${file.name}")
        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                return ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }

    private fun insertVideoIndexEntry(
        context: Context,
        file: File,
        relativePath: String = PUBLIC_VIDEO_RELATIVE_PATH,
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Video.Media.SIZE, file.length())
            put(MediaStore.Video.Media.DATE_MODIFIED, file.lastModified() / 1000L)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
        }
        return context.contentResolver.insert(videoCollectionUri(), values)?.also { uri ->
            Log.i(TAG, "新建媒体库索引 uri=$uri name=${file.name}")
        }
    }

    private fun updateVideoIndexMetadata(context: Context, uri: Uri, file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.SIZE, file.length())
            put(MediaStore.Video.Media.DATE_MODIFIED, file.lastModified() / 1000L)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
        }
        context.contentResolver.update(uri, values, null, null)
    }

    private fun notifyVideoCollectionChanged(context: Context, uri: Uri?, file: File) {
        val collection = videoCollectionUri()
        context.contentResolver.notifyChange(collection, null)
        uri?.let { context.contentResolver.notifyChange(it, null) }
        @Suppress("DEPRECATION")
        runCatching {
            context.sendBroadcast(
                Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                    data = Uri.fromFile(file)
                },
            )
        }
    }

    fun scanVideoFiles(context: Context, vararg files: File) {
        val paths = files.filter { it.exists() && it.length() > 0L }
            .map { it.absolutePath }
            .toTypedArray()
        if (paths.isEmpty()) return
        MediaScannerConnection.scanFile(
            context.applicationContext,
            paths,
            arrayOf("video/mp4"),
        ) { path, uri ->
            Log.i(TAG, "MediaScanner 回调 path=$path uri=$uri")
        }
    }

    private data class VideoOutputInfo(
        val relativePath: String?,
        val sizeBytes: Long,
        val isPending: Int,
    )

    private fun queryVideoOutputInfo(context: Context, uri: Uri): VideoOutputInfo {
        val projection = arrayOf(
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.IS_PENDING,
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val pathCol = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val pendingCol = cursor.getColumnIndex(MediaStore.Video.Media.IS_PENDING)
                return VideoOutputInfo(
                    relativePath = if (pathCol >= 0) cursor.getString(pathCol) else null,
                    sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else -1L,
                    isPending = if (pendingCol >= 0) cursor.getInt(pendingCol) else -1,
                )
            }
        }
        return VideoOutputInfo(null, -1L, -1)
    }

    fun deleteVideoOutput(context: Context, output: DashcamVideoOutput) {
        runCatching { context.contentResolver.delete(output.uri, null, null) }
    }

    @Suppress("DEPRECATION")
    fun resolveVideoFile(context: Context, output: DashcamVideoOutput): File? {
        resolveDataPath(context, output.uri)?.let { path ->
            val file = File(path)
            if (file.exists()) return file
        }
        resolveFromRelativePath(context, output.uri)?.let { file ->
            if (file.exists()) return file
        }
        val fallback = File(publicVideoStorageDir(), output.displayName)
        return fallback.takeIf { it.exists() }
    }

    private fun resolveFromRelativePath(context: Context, uri: Uri): File? {
        val projection = arrayOf(
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DISPLAY_NAME,
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val pathCol = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val relative = if (pathCol >= 0) cursor.getString(pathCol) else null
                val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                if (!relative.isNullOrBlank() && !name.isNullOrBlank()) {
                    return File(Environment.getExternalStorageDirectory(), "$relative$name")
                }
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun resolveDataPath(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Video.Media.DATA),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
        return null
    }

    fun createAudioOutputFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(recordingsDir(context), "AUD_$stamp.m4a")
    }

    fun createVoiceNoteFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(recordingsDir(context), "AUD_$stamp.m4a")
    }

    fun createCompressTempFile(source: File): File =
        File(source.parentFile, "${source.name}.compressing")

    fun createPhotoFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(recordingsDir(context), "IMG_$stamp.jpg")
    }

    fun listPhotos(context: Context): List<DashcamPhoto> =
        recordingsDir(context)
            .listFiles { file ->
                file.isFile && (
                    file.extension.equals("jpg", ignoreCase = true) ||
                        file.extension.equals("jpeg", ignoreCase = true)
                    )
            }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                DashcamPhoto(
                    file = file,
                    displayName = file.name,
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified(),
                )
            }
            .orEmpty()

    fun listClips(context: Context): List<DashcamClip> {
        val videos = listVideoClipsFromMediaStore(context) +
            listMediaClipsFromDir(sdcard0VideoDir(), DashcamClipType.VIDEO, "mp4") +
            listMediaClipsFromDir(recordingsDir(context), DashcamClipType.VIDEO, "mp4")
        val audioDir = recordingsDir(context)
        val audios = listMediaClipsFromDir(audioDir, DashcamClipType.AUDIO, "m4a")
        return (videos + audios)
            .distinctBy { clip ->
                runCatching { clip.file.canonicalPath }
                    .getOrDefault(clip.file.absolutePath)
            }
            .sortedByDescending { it.lastModifiedMs }
    }

    private fun listVideoClipsFromMediaStore(context: Context): List<DashcamClip> {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.RELATIVE_PATH,
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%100MEDIA%")
        val sort = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        val resolver = context.contentResolver
        val clips = mutableListOf<DashcamClip>()
        resolver.query(collection, projection, selection, args, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                if (!name.endsWith(".mp4", ignoreCase = true)) continue
                val uri = ContentUris.withAppendedId(collection, id)
                val size = cursor.getLong(sizeCol)
                val modifiedSec = cursor.getLong(modifiedCol)
                val file = resolveVideoFile(context, DashcamVideoOutput(uri, name))
                    ?: File(publicVideoStorageDir(), name)
                clips.add(
                    DashcamClip(
                        file = file,
                        uri = uri,
                        displayName = name,
                        sizeBytes = size,
                        lastModifiedMs = modifiedSec * 1000L,
                        type = DashcamClipType.VIDEO,
                    ),
                )
            }
        }
        return clips
    }

    private fun listMediaClipsFromDir(
        dir: File,
        type: DashcamClipType,
        extension: String,
    ): List<DashcamClip> =
        dir.listFiles { file ->
            file.isFile && file.extension.equals(extension, ignoreCase = true)
        }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                DashcamClip(
                    file = file,
                    uri = null,
                    displayName = file.name,
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified(),
                    type = type,
                )
            }
            .orEmpty()
}
