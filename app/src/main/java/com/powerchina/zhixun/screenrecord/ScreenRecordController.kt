package com.powerchina.zhixun.screenrecord

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object ScreenRecordController {

    private const val TAG = "ScreenRecord"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    private val _lastSavedPath = MutableStateFlow<String?>(null)
    val lastSavedPath: StateFlow<String?> = _lastSavedPath.asStateFlow()

    private var cachedResultCode: Int? = null
    private var cachedResultData: Intent? = null

    private var tickerJob: Job? = null

    fun hasCachedConsent(): Boolean = cachedResultCode != null && cachedResultData != null

    fun createCaptureIntent(activity: Activity): Intent {
        val manager = activity.getSystemService(MediaProjectionManager::class.java)
        return manager.createScreenCaptureIntent()
    }

    fun onConsentGranted(resultCode: Int, resultData: Intent) {
        cachedResultCode = resultCode
        cachedResultData = resultData
    }

    fun startWithCachedConsent(context: Context) {
        val code = cachedResultCode ?: return
        val data = cachedResultData ?: return
        start(context, code, data)
    }

    fun start(context: Context, resultCode: Int, resultData: Intent) {
        Log.i(TAG, "启动录屏")
        onConsentGranted(resultCode, resultData)
        context.startForegroundService(
            ScreenRecordService.startIntent(context, resultCode, resultData),
        )
    }

    fun stop(context: Context) {
        Log.i(TAG, "停止录屏")
        context.startService(ScreenRecordService.stopIntent(context))
    }

    fun releaseSession(context: Context) {
        Log.i(TAG, "释放录屏授权")
        cachedResultCode = null
        cachedResultData = null
        context.startService(ScreenRecordService.releaseIntent(context))
    }

    internal fun onRecordingStarted() {
        _isRecording.value = true
        _elapsedSeconds.value = 0
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(1_000)
                _elapsedSeconds.value += 1
            }
        }
    }

    internal fun onRecordingStopped(savedPath: String?) {
        tickerJob?.cancel()
        tickerJob = null
        _isRecording.value = false
        _elapsedSeconds.value = 0
        if (savedPath != null) {
            _lastSavedPath.value = savedPath
        }
        Log.i(TAG, "录屏结束 saved=$savedPath")
    }

    internal fun onProjectionRevoked() {
        cachedResultCode = null
        cachedResultData = null
        tickerJob?.cancel()
        tickerJob = null
        _isRecording.value = false
        _elapsedSeconds.value = 0
        Log.w(TAG, "录屏授权已撤销")
    }

    fun consumeSavedPath(): String? {
        val path = _lastSavedPath.value
        _lastSavedPath.value = null
        return path
    }

    fun formatElapsed(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%02d:%02d".format(m, s)
    }
}
