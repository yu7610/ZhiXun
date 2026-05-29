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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.powerchina.zhixun.R
import java.util.Locale

private val HeaderBg = Color(0xFF1E293B)
private val TabBlue = Color(0xFF2196F3)
private val TabPurple = Color(0xFF7E57C2)
private val TabOrange = Color(0xFFFF9800)
private val TabGray = Color(0xFF546E7A)

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
        containerColor = HeaderBg,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LocationTopBar(onBack = onBack)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0x33000000), RoundedCornerShape(12.dp)),
            ) {
                BaiduMapContainer(
                    uiState = uiState,
                    modifier = Modifier.fillMaxSize(),
                )

                LocationInfoCard(
                    coordinate = uiState.coordinateText,
                    speed = uiState.speedText,
                    altitude = uiState.altitudeText,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )

                when (uiState.tab) {
                    LocationTab.HISTORY -> {
                        HistoryPanel(
                            history = uiState.history,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth(0.92f)
                                .padding(12.dp),
                        )
                    }
                    else -> {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            RiskCard(
                                title = uiState.riskTitle,
                                message = uiState.riskMessage,
                                active = uiState.riskActive,
                                modifier = Modifier.weight(1f),
                            )
                            TrackStatsCard(
                                stats = uiState.todayStats,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            LocationBottomBar(
                selected = uiState.tab,
                onSelect = viewModel::selectTab,
            )
        }
    }
}

@Composable
private fun LocationTopBar(
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HeaderBg)
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(TabBlue)
                .clickable(onClick = onBack)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.location_back),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            text = stringResource(R.string.location_track_title),
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.location_gps_badge),
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(Color(0xFF43A047), RoundedCornerShape(14.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun LocationInfoCard(
    coordinate: String,
    speed: String,
    altitude: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(min = 148.dp, max = 188.dp)
            .shadow(2.dp, RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.96f), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
            .padding(6.dp),
    ) {
        Text(
            text = stringResource(R.string.location_info_title),
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
            .shadow(1.dp, RoundedCornerShape(8.dp))
            .background(background, RoundedCornerShape(8.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Text(
            title,
            color = Color(0xFFD32F2F),
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            message,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            color = Color(0xFF424242),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TrackStatsCard(
    stats: DailyTrackStats,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .shadow(1.dp, RoundedCornerShape(8.dp))
            .background(Color(0xFFE3F2FD), RoundedCornerShape(8.dp))
            .border(1.5.dp, Color(0xFF64B5F6), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Text(
            stringResource(R.string.location_today_track),
            color = Color(0xFF1976D2),
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "${stringResource(R.string.location_mileage)}:${stats.mileageKm.format1()}km | " +
                "${stringResource(R.string.location_stay)}:${stats.stayMinutes}min",
            fontSize = 9.sp,
            color = Color(0xFF37474F),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            text = "${stringResource(R.string.location_points)}:${stats.pointCount}",
            fontSize = 9.sp,
            color = Color(0xFF37474F),
        )
    }
}

@Composable
private fun HistoryPanel(
    history: List<DailyTrackHistory>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.96f), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(
            stringResource(R.string.location_history_title),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (history.isEmpty()) {
            Text(stringResource(R.string.location_history_empty), color = Color.Gray, fontSize = 13.sp)
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.height(160.dp),
            ) {
                items(history) { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF5F5F5), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                    ) {
                        Text(item.dateLabel, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(
                            "${item.mileageKm.format1()}km · 停留${item.stayMinutes}min · ${item.pointCount}点",
                            fontSize = 11.sp,
                            color = Color(0xFF616161),
                        )
                    }
                }
            }
        }
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
            .background(Color.White)
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
        BottomTabButton(
            text = stringResource(R.string.location_tab_history),
            color = TabGray,
            selected = selected == LocationTab.HISTORY,
            onClick = { onSelect(LocationTab.HISTORY) },
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

private fun Double.format1(): String = String.format(Locale.CHINA, "%.1f", this)
