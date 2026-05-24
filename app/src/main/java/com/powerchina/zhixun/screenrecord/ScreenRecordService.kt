package com.powerchina.zhixun.screenrecord

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.powerchina.zhixun.MainActivity
import com.powerchina.zhixun.R

class ScreenRecordService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: java.io.File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = readResultData(intent)
                startForegroundWithType(getString(R.string.screen_record_recording))
                ensureProjection(resultCode, data) ?: return START_NOT_STICKY
                startRecording()
            }
            ACTION_STOP -> stopRecording()
            ACTION_RELEASE -> releaseSession()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseRecorder()
        releaseProjection()
        super.onDestroy()
    }

    private fun readResultData(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun startForegroundWithType(contentText: String) {
        createChannel()
        val notification = buildNotification(contentText, ongoing = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureProjection(resultCode: Int, data: Intent?): MediaProjection? {
        if (data == null) {
            Log.e(TAG, "缺少录屏授权数据")
            stopSelf()
            return null
        }
        return try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val projection = manager.getMediaProjection(resultCode, data)
                ?: run {
                    Log.e(TAG, "getMediaProjection 返回 null")
                    ScreenRecordController.onProjectionRevoked()
                    stopSelf()
                    return null
                }
            mediaProjection = projection
            projection.registerCallback(projectionCallback, null)
            Log.i(TAG, "MediaProjection 已创建")
            projection
        } catch (e: Exception) {
            Log.e(TAG, "MediaProjection 创建失败", e)
            ScreenRecordController.onProjectionRevoked()
            stopSelf()
            null
        }
    }

    private fun startRecording() {
        releaseRecorder()
        try {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            val wm = getSystemService(WindowManager::class.java)
            wm.defaultDisplay.getRealMetrics(metrics)
            var width = metrics.widthPixels
            var height = metrics.heightPixels
            if (width % 2 != 0) width--
            if (height % 2 != 0) height--
            val density = metrics.densityDpi

            outputFile = ScreenRecordingStore.createOutputFile(this)
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(6_000_000)
                setOutputFile(outputFile!!.absolutePath)
                prepare()
            }
            mediaRecorder = recorder

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ZhiXunScreenRecord",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                null,
            )
            recorder.start()
            ScreenRecordController.onRecordingStarted()
            updateNotification(getString(R.string.screen_record_recording))
            Log.i(TAG, "录屏开始 ${outputFile!!.name} ${width}x$height")
        } catch (e: Exception) {
            Log.e(TAG, "录屏启动失败", e)
            ScreenRecordController.onRecordingStopped(null)
            releaseSession()
        }
    }

    private fun stopRecording() {
        val saved = outputFile?.absolutePath
        releaseRecorder()
        releaseProjection()
        ScreenRecordController.onRecordingStopped(saved)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "录屏已停止，授权已缓存可复用")
    }

    private fun releaseSession() {
        releaseRecorder()
        releaseProjection()
        ScreenRecordController.onProjectionRevoked()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseRecorder() {
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "MediaRecorder stop", e)
            outputFile?.delete()
            outputFile = null
        }
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    private fun releaseProjection() {
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection 已停止")
            releaseRecorder()
            releaseProjection()
            ScreenRecordController.onProjectionRevoked()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            NOTIFICATION_ID,
            buildNotification(contentText, ongoing = true),
        )
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.screen_record_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, ongoing: Boolean): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.feature_screen_record))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(pending)
            .setOngoing(ongoing)
            .build()
    }

    companion object {
        private const val TAG = "ScreenRecordService"
        private const val CHANNEL_ID = "screen_record"
        private const val NOTIFICATION_ID = 0x5343

        private const val ACTION_START = "com.powerchina.zhixun.screenrecord.START"
        private const val ACTION_STOP = "com.powerchina.zhixun.screenrecord.STOP"
        private const val ACTION_RELEASE = "com.powerchina.zhixun.screenrecord.RELEASE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        fun startIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_STOP
            }

        fun releaseIntent(context: Context): Intent =
            Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_RELEASE
            }
    }
}
