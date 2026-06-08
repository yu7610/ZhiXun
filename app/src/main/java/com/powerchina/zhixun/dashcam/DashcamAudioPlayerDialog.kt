package com.powerchina.zhixun.dashcam

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.powerchina.zhixun.R

@Composable
fun DashcamAudioPlayerDialog(
    clip: DashcamClip,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val uri = remember(clip.file.absolutePath) {
        DashcamRecordingStore.uriForFile(context, clip.file)
    }
    var isPlaying by remember(uri) { mutableStateOf(false) }
    var errorText by remember(uri) { mutableStateOf<String?>(null) }

    DisposableEffect(uri) {
        val player = MediaPlayer()
        try {
            player.setDataSource(context, uri)
            player.setOnPreparedListener {
                it.start()
                isPlaying = true
            }
            player.setOnCompletionListener {
                isPlaying = false
            }
            player.setOnErrorListener { _, _, _ ->
                errorText = "播放失败"
                isPlaying = false
                true
            }
            player.prepareAsync()
        } catch (e: Exception) {
            errorText = e.message ?: "播放失败"
        }
        onDispose {
            runCatching {
                player.stop()
                player.release()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1A1A1A)),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.dashcam_audio_playing),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = when {
                        errorText != null -> errorText!!
                        isPlaying -> stringResource(R.string.dashcam_audio_status_playing)
                        else -> stringResource(R.string.dashcam_audio_status_loading)
                    },
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 12.dp),
                    textAlign = TextAlign.Center,
                )
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.dashcam_playback_close),
                    tint = Color.White,
                )
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                color = Color.Black.copy(alpha = 0.6f),
            ) {
                Text(
                    text = clip.displayName,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
