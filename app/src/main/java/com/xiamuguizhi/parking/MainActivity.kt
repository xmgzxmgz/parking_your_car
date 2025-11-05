package com.xiamuguizhi.parking

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.xiamuguizhi.parking.data.ParkingDataStore
import com.xiamuguizhi.parking.ui.theme.ParkingTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import androidx.compose.ui.viewinterop.AndroidView
import android.location.Location
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment.Companion.Center
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer

/**
 * MainActivity
 * 用途：应用入口，负责 UI、权限请求、定位保存、照片拍摄与数据清除。
 * 参数：无。
 * 返回值：无。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dataStore = ParkingDataStore(this)
        setContent {
            val record by dataStore.recordFlow.collectAsState(initial = null)
            val scope = rememberCoroutineScope()

            // 主题基于定位（若有）自动切换
            val themeLat = record?.lat
            val themeLon = record?.lon

            ParkingTheme(latitude = themeLat, longitude = themeLon) {
                MainScreen(
                    record = record,
                    requestLocationAndSave = { floor, spot ->
                        scope.launch {
                            val loc = getCurrentLocation(this@MainActivity) ?: return@launch
                            val address = withContext(Dispatchers.IO) { reverseGeocode(this@MainActivity, loc.first, loc.second) }
                            dataStore.saveRecord(
                                lat = loc.first,
                                lon = loc.second,
                                floor = floor,
                                spot = spot,
                                address = address,
                                timestamp = System.currentTimeMillis(),
                                photoUris = record?.photoUris ?: emptyList()
                            )
                        }
                    },
                    appendPhoto = { uri ->
                        scope.launch { dataStore.appendPhoto(uri.toString()) }
                    },
                    clearRecord = {
                        scope.launch { dataStore.clear() }
                    }
                )
            }
        }
    }

    /**
     * 获取当前高精度定位（FusedLocationProviderClient）
     * @param context 上下文
     * @return Pair<Double, Double>? 纬度与经度，可能为空（失败）
     */
    private suspend fun getCurrentLocation(context: Context): Pair<Double, Double>? {
        // 运行时权限请求在 UI 中发起，这里假设已获得权限
        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val loc = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
            if (loc != null) loc.latitude to loc.longitude else null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    /**
     * 反向地理编码，获取地址字符串（可失败返回 null）
     * @param context 上下文
     * @param lat 纬度
     * @param lon 经度
     * @return String? 地址
     */
    private fun reverseGeocode(context: Context, lat: Double, lon: Double): String? {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val list = geocoder.getFromLocation(lat, lon, 1)
            list?.firstOrNull()?.getAddressLine(0)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/**
 * MainScreen
 * 用途：主界面，展示保存状态；支持标记、拍照、清除。
 * 参数：
 *  - record 当前保存的停车记录
 *  - requestLocationAndSave 调用定位并保存记录（含楼层与车位）
 *  - appendPhoto 追加一张照片 Uri
 *  - clearRecord 清除记录回到初始界面
 * 返回值：无。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    record: ParkingDataStore.ParkingRecord?,
    requestLocationAndSave: (String?, String?) -> Unit,
    appendPhoto: (Uri) -> Unit,
    clearRecord: () -> Unit
) {
    val scope = rememberCoroutineScope()

    // 权限请求器
    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    // 拍照：使用 MediaStore 创建图片并拍摄
    val context = LocalContext.current
    var pendingPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) pendingPhotoUri?.let { appendPhoto(it) }
    }

    val createImageUri: () -> Uri? = {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "parking_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "停车定位") },
                navigationIcon = {
                    if (record != null) {
                        TextButton(onClick = clearRecord) { Text(text = "我已上车") }
                    }
                }
            )
        }
    ) { padding ->
        // 全局状态：当前定位，供列表中多个项使用
        val currentLocationState = remember { mutableStateOf<Pair<Double, Double>?>(null) }
        val context = LocalContext.current

        // 使用 LazyColumn 防止可能的视图重叠，并提供更好的滚动体验
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
            // 顶部展示地图
            LaunchedEffect(Unit) {
                // 配置 osmdroid UserAgent
                Configuration.getInstance().userAgentValue = context.packageName
            }
            LocationUpdates(currentLocationState)
            ParkingMap(
                current = currentLocationState.value,
                parking = record?.let { it.lat to it.lon },
                onSetParking = { lat, lon ->
                    // 允许用户通过地图长按更新停车位置（仅在已有记录或输入后使用）
                    if (record != null) {
                        // 保持其他信息不变，仅更新经纬度
                        scope.launch {
                            val dataStore = ParkingDataStore(context)
                            dataStore.saveRecord(
                                lat = lat,
                                lon = lon,
                                floor = record.floor,
                                spot = record.spot,
                                address = record.address,
                                timestamp = record.timestamp,
                                photoUris = record.photoUris
                            )
                        }
                    }
                }
            )
            }
            item {
            if (record == null) {
                var floor by remember { mutableStateOf<String?>(null) }
                var spot by remember { mutableStateOf<String?>(null) }

                Text(text = "请先输入车位号，然后在下方拍照或进入下一步设置停车位置")
                FloorSelector(selected = floor, onSelect = { floor = it })
                OutlinedTextField(
                    value = spot ?: "",
                    onValueChange = { spot = it },
                    label = { Text("车位号") }
                )
                Button(onClick = {
                    // 请求定位与摄像头权限
                    requestPermissions.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.CAMERA
                    ))
                    requestLocationAndSave(floor, spot)
                }) {
                    Text("保存并进入详情")
                }
            } else {
                // 展示记录信息
                Text(text = "记录时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(record.timestamp))}")
                record.spot?.takeIf { it.isNotBlank() }?.let { Text(text = "车位号：$it") }
                // 仅显示经纬度，不再展示逆地理地址
                Text(text = "停车位置：${record.lat}, ${record.lon}")
                // 实时距离（米）
                val cur = currentLocationState.value
                val distanceMeters = remember(cur, record) {
                    cur?.let { d ->
                        haversineDistanceMeters(d.first, d.second, record.lat, record.lon)
                    }
                }
                distanceMeters?.let { Text(text = "与停车位置距离：${"%.1f".format(it)} 米") }

                // 照片区域
                Text(text = "已添加的照片：")
                if (record.photoUris.isEmpty()) {
                    Text(text = "暂无照片")
                } else {
                    var previewUri by remember { mutableStateOf<String?>(null) }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(record.photoUris) { uri ->
                            Image(
                                painter = rememberAsyncImagePainter(model = uri),
                                contentDescription = null,
                                modifier = Modifier.size(100.dp).clickable { previewUri = uri },
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                    // 全屏无边框预览，点击退出，支持简单缩放
                    if (previewUri != null) {
                        val scale = remember { mutableStateOf(1f) }
                        val state = rememberTransformableState { zoomChange, _, _ ->
                            scale.value = (scale.value * zoomChange).coerceIn(1f, 5f)
                        }
                        Box(
                            modifier = Modifier.fillMaxSize().clickable { previewUri = null },
                            contentAlignment = Center
                        ) {
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black).clickable { previewUri = null }) {}
                            Image(
                                painter = rememberAsyncImagePainter(model = previewUri),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .transformable(state)
                                    .graphicsLayer(scaleX = scale.value, scaleY = scale.value),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        // 拍照
                        val newUri = createImageUri()
                        if (newUri != null) {
                            pendingPhotoUri = newUri
                            takePictureLauncher.launch(newUri)
                        }
                    }) { Text("拍摄照片") }
                }

                Button(onClick = { clearRecord() }, modifier = Modifier.fillMaxWidth()) {
                    Text("我已上车（清除记录）")
                }
            }
            }
        }
    }
}

/**
 * FloorSelector
 * 用途：选择楼层（地下一层/地下二层/地下三层/地面等）。
 * 参数：
 *  - selected 当前选中值
 *  - onSelect 选择回调
 * 返回值：无。
 */
@Composable
fun FloorSelector(selected: String?, onSelect: (String?) -> Unit) {
    val floors = listOf("地面", "地下一层", "地下二层", "地下三层")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        floors.forEach { f ->
            FilterChip(
                selected = selected == f,
                onClick = { onSelect(if (selected == f) null else f) },
                label = { Text(f) }
            )
        }
    }
}
/**
 * LocationUpdates
 * 用途：开启 FusedLocationProvider 实时更新，将当前经纬度写入状态。
 * 参数：state 当前经纬度的状态容器
 * 返回值：无。
 */
@Composable
private fun LocationUpdates(state: MutableState<Pair<Double, Double>?>) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(5000)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val loc = locationResult.lastLocation
                if (loc != null) {
                    state.value = loc.latitude to loc.longitude
                }
            }
        }
        try {
            client.requestLocationUpdates(request, callback, context.mainLooper)
        } catch (e: SecurityException) {
            // 未授权定位权限时忽略
        }
        onDispose {
            client.removeLocationUpdates(callback)
        }
    }
}

/**
 * ParkingMap
 * 用途：展示地图，标记当前位置与停车位置；支持长按设置停车位置。
 * 参数：
 *  - current 当前经纬度
 *  - parking 停车经纬度（可空）
 *  - onSetParking 长按地图设置停车点回调
 */
@Composable
fun ParkingMap(
    current: Pair<Double, Double>?,
    parking: Pair<Double, Double>?,
    onSetParking: (Double, Double) -> Unit
) {
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(240.dp),
        factory = { ctx ->
            val map = MapView(ctx)
            Configuration.getInstance().userAgentValue = ctx.packageName
            // 使用国内可访问的高德地图瓦片源（公开可访问端点）
            val amap = object : OnlineTileSourceBase(
                "AMAP",
                0, 19, 256, "",
                arrayOf("https://webrd02.is.autonavi.com/appmaptile")
            ) {
                override fun getTileURLString(pMapTileIndex: Long): String {
                    val x = MapTileIndex.getX(pMapTileIndex)
                    val y = MapTileIndex.getY(pMapTileIndex)
                    val z = MapTileIndex.getZoom(pMapTileIndex)
                    return "$baseUrl?lang=zh_cn&size=1&style=7&x=$x&y=$y&z=$z"
                }
            }
            map.setTileSource(amap)
            map.controller.setZoom(17.0)

            // 事件接收：长按设置停车位置
            val receiver = object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
                override fun longPressHelper(p: GeoPoint?): Boolean {
                    if (p != null) onSetParking(p.latitude, p.longitude)
                    return true
                }
            }
            map.overlays.add(MapEventsOverlay(receiver))
            map
        },
        update = { map ->
            // 清除旧覆盖物（保留事件 overlay 在[0]位置）
            while (map.overlays.size > 1) map.overlays.removeAt(1)
            var center: GeoPoint? = null
            current?.let { (lat, lon) ->
                val gp = GeoPoint(lat, lon)
                val marker = Marker(map).apply {
                    position = gp
                    title = "当前位置"
                }
                map.overlays.add(marker)
                center = gp
            }
            parking?.let { (lat, lon) ->
                val gp = GeoPoint(lat, lon)
                val marker = Marker(map).apply {
                    position = gp
                    title = "停车位置"
                }
                map.overlays.add(marker)
                // 连接线
                val line = Polyline().apply {
                    addPoint(center ?: gp)
                    addPoint(gp)
                }
                map.overlays.add(line)
            }
            center?.let { map.controller.setCenter(it) }
            map.invalidate()
        }
    )
}

/**
 * haversineDistanceMeters
 * 用途：计算两个经纬度之间的球面距离（单位：米）。
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