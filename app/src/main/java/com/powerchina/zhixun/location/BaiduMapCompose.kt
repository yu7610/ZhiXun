package com.powerchina.zhixun.location

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.BitmapDescriptor
import com.baidu.mapapi.map.BitmapDescriptorFactory
import com.baidu.mapapi.map.CircleOptions
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.map.MarkerOptions
import com.baidu.mapapi.map.MyLocationConfiguration
import com.baidu.mapapi.map.MyLocationData
import com.baidu.mapapi.map.OverlayOptions
import com.baidu.mapapi.map.PolylineOptions
import com.baidu.mapapi.map.Stroke
import com.baidu.mapapi.model.LatLng

@Composable
fun BaiduMapContainer(
    uiState: LocationUiState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val startMarkerIcon = remember { createStartPointMarkerIcon(context) }
    val currentLocationIcon = remember { createCurrentLocationIcon(context) }
    val mapView = remember {
        MapView(context).apply {
            map.isMyLocationEnabled = true
            map.setMyLocationConfiguration(
                MyLocationConfiguration(
                    MyLocationConfiguration.LocationMode.NORMAL,
                    true,
                    currentLocationIcon,
                ),
            )
        }
    }
    var baiduMap by remember { mutableStateOf<BaiduMap?>(null) }

    DisposableEffect(lifecycleOwner, mapView) {
        baiduMap = mapView.map
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(
        uiState.currentLat,
        uiState.currentLng,
        uiState.tab,
        uiState.trackPoints,
        uiState.startPoint,
        uiState.fenceCenterLat,
        uiState.fenceCenterLng,
        uiState.fenceRadiusM,
        uiState.riskActive,
        baiduMap,
    ) {
        val map = baiduMap ?: return@LaunchedEffect
        val lat = uiState.currentLat ?: return@LaunchedEffect
        val lng = uiState.currentLng ?: return@LaunchedEffect

        map.clear()
        map.setMyLocationData(
            MyLocationData.Builder()
                .latitude(lat)
                .longitude(lng)
                .build(),
        )

        val overlays = mutableListOf<OverlayOptions>()

        uiState.startPoint?.let { start ->
            overlays.add(
                MarkerOptions()
                    .position(LatLng(start.latitude, start.longitude))
                    .icon(startMarkerIcon)
                    .title("起点"),
            )
        }

        if (uiState.tab == LocationTab.TRACK || uiState.tab == LocationTab.LOCATE) {
            val track = uiState.trackPoints.map { LatLng(it.latitude, it.longitude) }
            if (track.size >= 2) {
                overlays.add(
                    PolylineOptions()
                        .width(8)
                        .color(0xFF2196F3.toInt())
                        .points(track),
                )
            }
        }

        if (uiState.tab == LocationTab.FENCE || uiState.riskActive) {
            val centerLat = uiState.fenceCenterLat ?: lat
            val centerLng = uiState.fenceCenterLng ?: lng
            overlays.add(
                CircleOptions()
                    .center(LatLng(centerLat, centerLng))
                    .radius(uiState.fenceRadiusM.toInt())
                    .fillColor(0x33F44336)
                    .stroke(Stroke(4, 0xFFE53935.toInt())),
            )
        }

        overlays.forEach { map.addOverlay(it) }

        val zoom = when (uiState.tab) {
            LocationTab.FENCE -> 17f
            LocationTab.TRACK -> if (uiState.trackPoints.size > 1) 16f else 18f
            else -> 18f
        }
        map.animateMapStatus(MapStatusUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom))
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
    )
}

private fun createStartPointMarkerIcon(context: Context): BitmapDescriptor {
    val density = context.resources.displayMetrics.density
    val width = (56 * density).toInt().coerceAtLeast(1)
    val height = (28 * density).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7E57C2")
        style = Paint.Style.FILL
    }
    canvas.drawRoundRect(
        RectF(0f, 0f, width.toFloat(), height.toFloat()),
        8 * density,
        8 * density,
        bgPaint,
    )
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12 * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    val textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText("起点", width / 2f, textY, textPaint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun createCurrentLocationIcon(context: Context): BitmapDescriptor {
    val density = context.resources.displayMetrics.density
    val size = (22 * density).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f
    val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center, outerPaint)
    val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, center * 0.72f, innerPaint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
