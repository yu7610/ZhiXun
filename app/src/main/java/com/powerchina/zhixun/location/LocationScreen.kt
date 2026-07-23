package com.powerchina.zhixun.location

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.powerchina.zhixun.R
private val TabBlue = Color(0xFF2196F3)
private val TabPurple = Color(0xFF7E57C2)
private val TabOrange = Color(0xFFFF9800)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun LocationScreen(
    onBack: () -> Unit,
    viewModel: LocationViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val permissionsState = rememberMultiplePermissionsState(
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
    )

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) {
            viewModel.onPermissionsGranted()
        } else {
            permissionsState.launchMultiplePermissionRequest()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        val message = uiState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearError()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Black,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            BaiduMapContainer(
                uiState = uiState,
                modifier = Modifier.fillMaxSize(),
            )

            FloatingBackButton(
                onBack = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(12.dp),
            )

            if (uiState.tab == LocationTab.LOCATE || uiState.tab == LocationTab.TRACK) {
                LocationInfoCard(
                    coordinate = uiState.coordinateText,
                    speed = uiState.speedText,
                    altitude = uiState.altitudeText,
                    title = if (uiState.tab == LocationTab.TRACK) {
                        stringResource(R.string.location_today_track)
                    } else {
                        stringResource(R.string.location_info_title)
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(12.dp),
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                if (uiState.riskMessage.isNotBlank()) {
                    RiskCard(
                        title = uiState.riskTitle,
                        message = uiState.riskMessage,
                        active = uiState.riskActive,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                LocationBottomBar(
                    selected = uiState.tab,
                    onSelect = viewModel::selectTab,
                )
            }
        }
    }
}

@Composable
private fun FloatingBackButton(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(TabBlue)
            .clickable(onClick = onBack)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.location_back),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun LocationInfoCard(
    coordinate: String,
    speed: String,
    altitude: String,
    title: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(min = 148.dp, max = 188.dp)
            .shadow(4.dp, RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.96f), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
            .padding(6.dp),
    ) {
        Text(
            text = title,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
        Spacer(modifier = Modifier.height(3.dp))
        InfoChip(
            text = "${stringResource(R.string.location_coord)} $coordinate",
            background = Color(0xFFE3F2FD),
        )
        Spacer(modifier = Modifier.height(3.dp))
        InfoChip(
            text = "${stringResource(R.string.location_speed)} $speed",
            background = Color(0xFFFFF9C4),
        )
        Spacer(modifier = Modifier.height(3.dp))
        InfoChip(
            text = "${stringResource(R.string.location_altitude)}: $altitude",
            background = Color(0xFFE8F5E9),
        )
    }
}

@Composable
private fun InfoChip(text: String, background: Color) {
    Text(
        text = text,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        color = Color(0xFF37474F),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 3.dp),
    )
}

@Composable
private fun RiskCard(
    title: String,
    message: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (active) Color(0xFFE53935) else Color(0xFFEF9A9A)
    val background = if (active) Color(0xFFFFEBEE) else Color(0xFFFFF5F5)
    Column(
        modifier = modifier
            .shadow(2.dp, RoundedCornerShape(8.dp))
            .background(background, RoundedCornerShape(8.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            title,
            color = Color(0xFFD32F2F),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            message,
            fontSize = 10.sp,
            lineHeight = 14.sp,
            color = Color(0xFF424242),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LocationBottomBar(
    selected: LocationTab,
    onSelect: (LocationTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.96f))
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BottomTabButton(
            text = stringResource(R.string.location_tab_locate),
            color = TabBlue,
            selected = selected == LocationTab.LOCATE,
            onClick = { onSelect(LocationTab.LOCATE) },
            modifier = Modifier.weight(1f),
        )
        BottomTabButton(
            text = stringResource(R.string.location_tab_fence),
            color = TabPurple,
            selected = selected == LocationTab.FENCE,
            onClick = { onSelect(LocationTab.FENCE) },
            modifier = Modifier.weight(1f),
        )
        BottomTabButton(
            text = stringResource(R.string.location_tab_track),
            color = TabOrange,
            selected = selected == LocationTab.TRACK,
            onClick = { onSelect(LocationTab.TRACK) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BottomTabButton(
    text: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(46.dp)
            .shadow(if (selected) 3.dp else 0.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) color else color.copy(alpha = 0.72f))
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = Color.White.copy(alpha = 0.6f),
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
