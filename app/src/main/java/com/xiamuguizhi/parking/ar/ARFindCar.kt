package com.xiamuguizhi.parking.ar

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Range
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import com.xiamuguizhi.parking.util.GeoMath

/**
 * ARFindCarScreen
 * 用途：在摄像头画面上叠加 HUD，实时显示车辆相对方位与距离。
 * 参数：
 *  - parking 停车点（WGS84，经纬度）；若为空则提示未设置
 *  - currentProvider 获取当前定位（WGS84）的回调函数，返回 Pair(lat, lon) 或 null
 * 返回：无
 */
@Composable
fun ARFindCarScreen(
    parking: Pair<Double, Double>?,
    currentProvider: suspend () -> Pair<Double, Double>?,
) {
    val context = LocalContext.current

    // 摄像头朝向（前/后）
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }

    // 设备航向角（0~360，0=正北，顺时针增加）
    var headingDegrees by remember { mutableStateOf(0f) }

    // 当前距离（米）与相对方位（度，-180~180，0=正前方）
    var distanceMeters by remember { mutableStateOf<Double?>(null) }
    var relativeBearing by remember { mutableStateOf<Float?>(null) }

    // 启动方向传感器（旋转向量）
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val rot = FloatArray(9)
                val orient = FloatArray(3)
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                SensorManager.getOrientation(rot, orient)
                // azimuth 弧度，转换到 0..360 度
                var azimuth = Math.toDegrees(orient[0].toDouble()).toFloat()
                if (azimuth < 0f) azimuth += 360f
                headingDegrees = azimuth
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }

    // 每秒更新距离与相对方位（>=1Hz）
    LaunchedEffect(parking, headingDegrees) {
        while (true) {
            val cur = currentProvider()
            if (parking != null && cur != null) {
                val d = GeoMath.haversineDistanceMeters(cur.first, cur.second, parking.first, parking.second)
                distanceMeters = d
                val bearing = bearingDegrees(cur.first, cur.second, parking.first, parking.second)
                val rel = normalizeAngleDegrees((bearing - headingDegrees).toFloat())
                relativeBearing = rel
            }
            delay(1000)
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 摄像头预览作为背景
        CameraPreview(modifier = Modifier.fillMaxSize(), lensFacing = lensFacing) { facing ->
            lensFacing = facing
        }

        // HUD：底部半透明黑色区域，箭头+文字的方位指示 + 距离
        HudOverlay(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(180.dp),
            relativeBearing = relativeBearing,
            distanceMeters = distanceMeters
        )
    }
}

/**
 * CameraPreview
 * 用途：以 CameraX 输出摄像头预览到屏幕背景；默认尝试 60fps；支持前后摄像头切换。
 * 参数：
 *  - modifier 组合修饰符
 *  - lensFacing 当前镜头朝向
 *  - onToggle 回调（可能用于未来 UI 控制镜头）
 * 返回：无
 */
@SuppressLint("MissingPermission")
@Composable
private fun CameraPreview(
    modifier: Modifier,
    lensFacing: Int,
    onToggle: (Int) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hasCamera = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            previewView.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            if (hasCamera) {
                val provider = ProcessCameraProvider.getInstance(ctx).get()
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                val builder = Preview.Builder()
                // 通过 Camera2Interop 尝试将帧率固定到 60fps（机型不支持时自动降级）
                Camera2Interop.Extender(builder).setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    if (Build.VERSION.SDK_INT >= 21) Range(60, 60) else Range(30, 30)
                )
                val preview = builder.build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview
                )
            }
            previewView
        },
        update = {}
    )
}

/**
 * HudOverlay
 * 用途：底部半透明 HUD 展示箭头+文字的相对方位以及距离信息。
 * 参数：
 *  - modifier 修饰符
 *  - relativeBearing 相对方位角（-180~180，0=正前方）
 *  - distanceMeters 距离（米）
 * 返回：无
 */
@Composable
private fun HudOverlay(
    modifier: Modifier,
    relativeBearing: Float?,
    distanceMeters: Double?
) {
    val bgColor = Color(0xAA000000)
    val textColor = Color.White
    val dirText = remember(relativeBearing) { bearingToText(relativeBearing) }

    Column(
        modifier = modifier.background(bgColor).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 方位箭头 + 文本
        Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
            val arrow = directionArrow(relativeBearing)
            Text(text = arrow + " " + (dirText ?: "未知方位"), style = MaterialTheme.typography.titleMedium, color = textColor)
        }
        // 距离显示（保留 1 位小数）
        val distText = distanceMeters?.let { String.format("距离：%.1f米", it) } ?: "距离：--"
        Text(text = distText, style = MaterialTheme.typography.titleSmall, color = textColor)
    }
}

/**
 * bearingToText
 * 用途：将相对方位角映射到中文文案（正前方、左前方等）。
 * 参数：
 *  - rel 相对角度（-180~180，0=前）
 * 返回：中文方位文案
 */
private fun bearingToText(rel: Float?): String? {
    if (rel == null) return null
    val a = rel
    return when {
        abs(a) <= 15f -> "正前方"
        a in 15f..75f -> "右前方"
        a in 75f..105f -> "正右侧"
        a in 105f..165f -> "右后方"
        a >= 165f -> "正后方"
        a in -75f..-15f -> "左前方"
        a in -105f..-75f -> "正左侧"
        a in -165f..-105f -> "左后方"
        a <= -165f -> "正后方"
        else -> "未知方位"
    }
}

/**
 * directionArrow
 * 用途：根据相对方位角返回箭头字符（简化 HUD 视觉）。
 * 参数：
 *  - rel 相对角度
 * 返回：箭头字符
 */
private fun directionArrow(rel: Float?): String {
    if (rel == null) return "⬆"
    val a = rel
    return when {
        abs(a) <= 15f -> "⬆"
        a in 15f..75f -> "↗"
        a in 75f..105f -> "➡"
        a in 105f..165f -> "↘"
        a >= 165f -> "⬇"
        a in -75f..-15f -> "↖"
        a in -105f..-75f -> "⬅"
        a in -165f..-105f -> "↙"
        a <= -165f -> "⬇"
        else -> "⬆"
    }
}

/**
 * bearingDegrees
 * 用途：计算从点 A 指向点 B 的方位角（0~360，0=北）。
 * 参数：
 *  - lat1/lon1 起点（WGS84）
 *  - lat2/lon2 终点（WGS84）
 * 返回：方位角（度）
 */
private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lon2 - lon1)
    val y = sin(dl) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dl)
    var br = Math.toDegrees(atan2(y, x)).toFloat()
    if (br < 0f) br += 360f
    return br
}

/**
 * normalizeAngleDegrees
 * 用途：将角度归一化到 [-180, 180] 区间。
 */
private fun normalizeAngleDegrees(a: Float): Float {
    var v = ((a + 180f) % 360f)
    if (v < 0f) v += 360f
    return v - 180f
}

/**
 * haversineDistanceMeters
 * 用途：计算两点球面距离（米）。
 */
private fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    return R * c
}