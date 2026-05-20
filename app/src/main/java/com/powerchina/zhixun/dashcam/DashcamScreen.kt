package com.powerchina.zhixun.dashcam

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.powerchina.zhixun.R
import com.powerchina.zhixun.data.ConfigManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

private val BlueBackBtn = Color(0xFF3D7BDB)
private val RecRed = Color(0xFFD32F2F)
private val BtnStartGreen = Color(0xFF43A047)
private val BtnPurple = Color(0xFF7E57C2)
private val BtnBlue = Color(0xFF1E88E5)
private val BtnOrange = Color(0xFFFB8C00)
private val BtnPlayback = Color(0xFF455A64)
private val PanelBg = Color(0xFFF3F4F6)

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DashcamScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DashcamViewModel = viewModel(),
) {
    val context = LocalContext.current
    val isRecording by viewModel.isRecording.collectAsState()
    val elapsedSeconds by viewModel.elapsedSeconds.collectAsState()
    val clips by viewModel.clips.collectAsState()
    val message by viewModel.message.collectAsState()

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var playingClip by remember { mutableStateOf<DashcamClip?>(null) }
    var showPlaybackSheet by remember { mutableStateOf(false) }

    val permissionsState = rememberMultiplePermissionsState(listOf(Manifest.permission.CAMERA))
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deviceId = remember { ConfigManager(context).loadConfig().macAddress.ifBlank { "未知设备" } }
    val clockText = rememberClockText()
    val storageInfo = rememberStorageInfo(context)
    val networkConnected = rememberNetworkConnected(context)

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.clearMessage()
    }

    LaunchedEffect(Unit) {
        if (!permissionsState.allPermissionsGranted) {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (!permissionsState.allPermissionsGranted) return@LaunchedEffect
        viewModel.tryAutoStartRecording()
    }

    LaunchedEffect(Unit) {
        Log.d(VideoKeyReceiver.TAG, "DashcamScreen: 开始监听物理录像键事件")
        DashcamVideoKeyEvents.events.collect { keyAction ->
            if (!permissionsState.allPermissionsGranted) return@collect
            viewModel.onVideoKey(keyAction)
        }
    }

    playingClip?.let { clip ->
        DashcamVideoPlayerDialog(clip = clip, onDismiss = { playingClip = null })
    }

    if (showPlaybackSheet) {
        PlaybackBottomSheet(
            clips = clips,
            onDismiss = { showPlaybackSheet = false },
            onPlay = { clip ->
                showPlaybackSheet = false
                if (clip.file.exists() && clip.file.length() > 0L) {
                    playingClip = clip
                } else {
                    scope.launch { snackbar.showSnackbar("录像文件不存在") }
                }
            },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
            ) {
                if (permissionsState.allPermissionsGranted) {
                    DashcamCameraPreview(
                        lensFacing = lensFacing,
                        modifier = Modifier.fillMaxSize(),
                        onSessionReady = { session ->
                            viewModel.bindCameraSession(session)
                            viewModel.ensureRecordingContinues()
                        },
                    )
                    DashcamTopBar(
                        isRecording = isRecording,
                        elapsedSeconds = elapsedSeconds,
                        onBack = onBack,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                    ) {
                        StatusInfoBar(
                            storageTotal = storageInfo.first,
                            storageAvailable = storageInfo.second,
                            networkConnected = networkConnected,
                        )
                        PreviewInfoOverlay(
                            clockText = clockText,
                            deviceId = deviceId,
                        )
                    }
                } else {
                    PermissionPlaceholder(
                        onRequest = { permissionsState.launchMultiplePermissionRequest() },
                        modifier = Modifier.fillMaxSize(),
                    )
                    DashcamTopBar(
                        isRecording = false,
                        elapsedSeconds = 0,
                        onBack = onBack,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            }

            ControlButtonPanel(
                enabled = permissionsState.allPermissionsGranted,
                isRecording = isRecording,
                onAudio = {
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.dashcam_feature_coming)) }
                },
                onPhoto = { viewModel.takePhoto() },
                onRecordToggle = { viewModel.toggleRecording() },
                onUpload = {
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.dashcam_feature_coming)) }
                },
                onPlayback = {
                    if (clips.isEmpty()) {
                        scope.launch { snackbar.showSnackbar(context.getString(R.string.dashcam_no_clip_playback)) }
                    } else {
                        showPlaybackSheet = true
                    }
                },
            )
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun DashcamTopBar(
    isRecording: Boolean,
    elapsedSeconds: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(BlueBackBtn)
                .clickable(onClick = onBack)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.dashcam_back_btn),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Text(
            text = stringResource(R.string.dashcam_title),
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )

        if (isRecording) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(RecRed)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.dashcam_rec_badge, formatRecDuration(elapsedSeconds)),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
        } else {
            Spacer(modifier = Modifier.widthIn(min = 72.dp))
        }
    }
}

@Composable
private fun PreviewInfoOverlay(
    clockText: String,
    deviceId: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = clockText,
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = stringResource(R.string.dashcam_gps_placeholder),
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = stringResource(R.string.dashcam_device_id, deviceId),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 11.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun StatusInfoBar(
    storageTotal: String,
    storageAvailable: String,
    networkConnected: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.dashcam_resolution),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.dashcam_storage, storageTotal, storageAvailable),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 13.sp,
        )
        Text(
            text = stringResource(
                if (networkConnected) R.string.dashcam_network_connected else R.string.dashcam_network_offline,
            ),
            color = if (networkConnected) Color(0xFF81C784) else Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ControlButtonPanel(
    enabled: Boolean,
    isRecording: Boolean,
    onAudio: () -> Unit,
    onPhoto: () -> Unit,
    onRecordToggle: () -> Unit,
    onUpload: () -> Unit,
    onPlayback: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PanelBg)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DashcamActionButton(
                text = stringResource(R.string.dashcam_btn_audio),
                color = BtnPurple,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = onAudio,
            )
            DashcamActionButton(
                text = stringResource(R.string.dashcam_btn_photo),
                color = BtnBlue,
                enabled = enabled,
                modifier = Modifier.weight(1.15f),
                height = 56.dp,
                onClick = onPhoto,
            )
            DashcamActionButton(
                text = stringResource(
                    if (isRecording) R.string.dashcam_btn_stop else R.string.dashcam_btn_start,
                ),
                color = if (isRecording) RecRed else BtnStartGreen,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = onRecordToggle,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DashcamActionButton(
                text = stringResource(R.string.dashcam_btn_upload),
                color = BtnOrange,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = onUpload,
            )
            DashcamActionButton(
                text = stringResource(R.string.dashcam_btn_playback),
                color = BtnPlayback,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = onPlayback,
            )
        }
    }
}

@Composable
private fun DashcamActionButton(
    text: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 48.dp,
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) color else color.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackBottomSheet(
    clips: List<DashcamClip>,
    onDismiss: () -> Unit,
    onPlay: (DashcamClip) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1A1A),
    ) {
        Text(
            text = stringResource(R.string.dashcam_saved_clips),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(clips, key = { it.file.absolutePath }) { clip ->
                ClipRow(clip = clip, onClick = { onPlay(clip) })
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ClipRow(clip: DashcamClip, onClick: () -> Unit) {
    val time = remember(clip.lastModifiedMs) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(clip.lastModifiedMs))
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(clip.displayName, color = Color.White, fontWeight = FontWeight.Medium)
                Text(
                    "${formatFileSize(clip.sizeBytes)} · $time",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                )
            }
            Text("播放", color = Color(0xFF90CAF9), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PermissionPlaceholder(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xFF1A1A1A))
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.dashcam_permission_hint),
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRequest, modifier = Modifier.padding(top = 12.dp)) {
            Text("授予权限", color = Color.White)
        }
    }
}

@Composable
private fun rememberClockText(): String {
    var text by remember {
        mutableStateOf(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
    }
    LaunchedEffect(Unit) {
        while (true) {
            text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            kotlinx.coroutines.delay(1000)
        }
    }
    return text
}

@Composable
private fun rememberStorageInfo(context: Context): Pair<String, String> {
    return remember {
        runCatching {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            val stat = StatFs(dir.absolutePath)
            val total = stat.totalBytes
            val avail = stat.availableBytes
            formatStorageGb(total) to formatStorageGb(avail)
        }.getOrElse { "—" to "—" }
    }
}

@Composable
private fun rememberNetworkConnected(context: Context): Boolean {
    return remember {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork ?: return@remember false
        val caps = cm.getNetworkCapabilities(network) ?: return@remember false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}

private fun formatRecDuration(totalSeconds: Int): String {
    val hours = TimeUnit.SECONDS.toHours(totalSeconds.toLong())
    val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds.toLong()) % 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
    return String.format(Locale.getDefault(), "%.1f GB", mb / 1024.0)
}

private fun formatStorageGb(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1) {
        String.format(Locale.getDefault(), "%.0fGB", gb)
    } else {
        String.format(Locale.getDefault(), "%.0fMB", bytes / (1024.0 * 1024.0))
    }
}
