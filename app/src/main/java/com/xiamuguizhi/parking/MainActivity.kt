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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.MapView as AMapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.xiamuguizhi.parking.ar.ARFindCarScreen

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
    var showAR by remember { mutableStateOf(false) }

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
            // 地图卡片化，避免与下方内容在视觉与触摸上产生重叠
            Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
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
            Spacer(modifier = Modifier.height(12.dp))
            }
            item {
            if (record == null) {
                var floor by remember { mutableStateOf<String?>(null) }
                var spot by remember { mutableStateOf<String?>(null) }

                Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("保存并进入详情")
                        }
                    }
                }
            } else {
                // 展示记录信息（卡片）
                Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = "记录时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(record.timestamp))}")
                        record.spot?.takeIf { it.isNotBlank() }?.let { Text(text = "车位号：$it") }
                        Text(text = "停车位置：${record.lat}, ${record.lon}")
                        // 实时距离（米）
                        val cur = currentLocationState.value
                        val distanceMeters = remember(cur, record) {
                            cur?.let { d -> haversineDistanceMeters(d.first, d.second, record.lat, record.lon) }
                        }
                        distanceMeters?.let { Text(text = "与停车位置距离：${"%.1f".format(it)} 米") }

                        // 方向提示：从当前位置前往停车位置应朝向
                        val directionText = remember(cur, record) {
                            cur?.let { d -> bearingDirection(d.first, d.second, record.lat, record.lon) }
                        }
                        directionText?.let { Text(text = "前往停车位置方向：$it") }

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
                                        modifier = Modifier.size(100.dp).clip(RoundedCornerShape(8.dp)).clickable { previewUri = uri },
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                            // 全屏无边框预览（Dialog），点击退出，支持缩放
                            if (previewUri != null) {
                                Dialog(
                                    onDismissRequest = { previewUri = null },
                                    properties = DialogProperties(usePlatformDefaultWidth = false)
                                ) {
                                    val scale = remember { mutableStateOf(1f) }
                                    val state = rememberTransformableState { zoomChange, _, _ ->
                                        scale.value = (scale.value * zoomChange).coerceIn(1f, 5f)
                                    }
                                    Box(
                                        modifier = Modifier.fillMaxSize().background(Color.Black).clickable { previewUri = null },
                                        contentAlignment = Center
                                    ) {
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

                    TextButton(onClick = { showAR = true }) { Text("进入AR寻车") }
                }

                Button(onClick = { clearRecord() }, modifier = Modifier.fillMaxWidth()) {
                    Text("我已上车（清除记录）")
                }

                if (showAR) {
                    Dialog(onDismissRequest = { showAR = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        Box(Modifier.fillMaxSize()) {
                            ARFindCarScreen(
                                parking = record.let { it.lat to it.lon },
                                currentProvider = {
                                    try {
                                        val client = LocationServices.getFusedLocationProviderClient(context)
                                        val loc = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                                        if (loc != null) loc.latitude to loc.longitude else null
                                    } catch (e: Exception) { null }
                                }
                            )
                            TextButton(
                                onClick = { showAR = false },
                                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                            ) { Text("关闭") }
                        }
                    }
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val amapKey = remember(context) { getAmapApiKey(context) }

    if (!amapKey.isNullOrBlank()) {
        // 优先使用：高德官方 SDK（GCJ-02，稳定可靠）
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(240.dp),
            factory = { ctx ->
                // 高德隐私合规初始化（必须在首次使用 SDK 前调用）
                MapsInitializer.updatePrivacyShow(ctx, true, true)
                MapsInitializer.updatePrivacyAgree(ctx, true)

                val mv = AMapView(ctx)
                mv.onCreate(null)
                // 如果当前已处于 RESUMED 状态，立即触发 onResume，避免首次渲染黑屏
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    mv.onResume()
                }
                val aMap = mv.map
                aMap.uiSettings.isZoomControlsEnabled = true
                aMap.moveCamera(CameraUpdateFactory.zoomTo(17f))
                aMap.setOnMapLongClickListener { ll ->
                    val (wLat, wLon) = gcj02ToWgs84(ll.latitude, ll.longitude)
                    onSetParking(wLat, wLon)
                }

                // 绑定生命周期，防止黑屏或资源泄漏
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        androidx.lifecycle.Lifecycle.Event.ON_RESUME -> mv.onResume()
                        androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> mv.onPause()
                        androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> mv.onDestroy()
                        else -> {}
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                mv
            },
            update = { mv ->
                val aMap = mv.map
                aMap.clear()
                var center: LatLng? = null
                current?.let { (lat, lon) ->
                    val (gLat, gLon) = wgs84ToGcj02(lat, lon)
                    val ll = LatLng(gLat, gLon)
                    aMap.addMarker(MarkerOptions().position(ll).title("当前位置"))
                    center = ll
                }
                parking?.let { (lat, lon) ->
                    val (gLat, gLon) = wgs84ToGcj02(lat, lon)
                    val ll = LatLng(gLat, gLon)
                    aMap.addMarker(MarkerOptions().position(ll).title("停车位置"))
                    center?.let { c -> aMap.addPolyline(PolylineOptions().add(c, ll)) }
                }
                center?.let { aMap.moveCamera(CameraUpdateFactory.newLatLng(it)) }
            }
        )
    } else {
        // 回退方案：osmdroid + 高德瓦片（无需 Key）
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(240.dp),
            factory = { ctx ->
                val map = MapView(ctx)
                Configuration.getInstance().userAgentValue = ctx.packageName
                val amap = object : OnlineTileSourceBase(
                    "AMAP",
                    0, 19, 256, "",
                    arrayOf(
                        "https://webrd01.is.autonavi.com/appmaptile",
                        "https://webrd02.is.autonavi.com/appmaptile",
                        "https://webrd03.is.autonavi.com/appmaptile",
                        "https://webrd04.is.autonavi.com/appmaptile"
                    )
                ) {
                    override fun getTileURLString(pMapTileIndex: Long): String {
                        val x = MapTileIndex.getX(pMapTileIndex)
                        val y = MapTileIndex.getY(pMapTileIndex)
                        val z = MapTileIndex.getZoom(pMapTileIndex)
                        return "$baseUrl?lang=zh_cn&size=1&scl=1&style=7&x=$x&y=$y&z=$z"
                    }
                }
                map.setTileSource(amap)
                map.setUseDataConnection(true)
                map.setMultiTouchControls(true)
                map.controller.setZoom(17.0)
                val receiver = object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
                    override fun longPressHelper(p: GeoPoint?): Boolean {
                        if (p != null) {
                            val (wgsLat, wgsLon) = gcj02ToWgs84(p.latitude, p.longitude)
                            onSetParking(wgsLat, wgsLon)
                        }
                        return true
                    }
                }
                map.overlays.add(MapEventsOverlay(receiver))
                map
            },
            update = { map ->
                while (map.overlays.size > 1) map.overlays.removeAt(1)
                var center: GeoPoint? = null
                current?.let { (lat, lon) ->
                    val (gcjLatCur, gcjLonCur) = wgs84ToGcj02(lat, lon)
                    val gp = GeoPoint(gcjLatCur, gcjLonCur)
                    val marker = Marker(map).apply { position = gp; title = "当前位置" }
                    map.overlays.add(marker)
                    center = gp
                }
                parking?.let { (lat, lon) ->
                    val (gcjLatPk, gcjLonPk) = wgs84ToGcj02(lat, lon)
                    val gp = GeoPoint(gcjLatPk, gcjLonPk)
                    val marker = Marker(map).apply { position = gp; title = "停车位置" }
                    map.overlays.add(marker)
                    val line = Polyline().apply { addPoint(center ?: gp); addPoint(gp) }
                    map.overlays.add(line)
                }
                center?.let { map.controller.setCenter(it) }
                map.invalidate()
            }
        )
    }
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

/**
 * 坐标系转换工具
 * 用途：在国内底图（GCJ-02/BD-09）与 GPS(WGS84)间相互转换，保证显示与计算一致。
 */
private fun outOfChina(lat: Double, lon: Double): Boolean {
    return lon !in 72.004..137.8347 || lat !in 0.8293..55.8271
}

private fun transformLat(x: Double, y: Double): Double {
    var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * kotlin.math.sqrt(kotlin.math.abs(x))
    ret += (20.0 * kotlin.math.sin(6.0 * x * Math.PI) + 20.0 * kotlin.math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
    ret += (20.0 * kotlin.math.sin(y * Math.PI) + 40.0 * kotlin.math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
    ret += (160.0 * kotlin.math.sin(y / 12.0 * Math.PI) + 320.0 * kotlin.math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
    return ret
}

private fun transformLon(x: Double, y: Double): Double {
    var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * kotlin.math.sqrt(kotlin.math.abs(x))
    ret += (20.0 * kotlin.math.sin(6.0 * x * Math.PI) + 20.0 * kotlin.math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
    ret += (20.0 * kotlin.math.sin(x * Math.PI) + 40.0 * kotlin.math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
    ret += (150.0 * kotlin.math.sin(x / 12.0 * Math.PI) + 300.0 * kotlin.math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
    return ret
}

private fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
    if (outOfChina(lat, lon)) return lat to lon
    val a = 6378245.0
    val ee = 0.00669342162296594323
    var dLat = transformLat(lon - 105.0, lat - 35.0)
    var dLon = transformLon(lon - 105.0, lat - 35.0)
    val radLat = lat / 180.0 * Math.PI
    var magic = kotlin.math.sin(radLat)
    magic = 1 - ee * magic * magic
    val sqrtMagic = kotlin.math.sqrt(magic)
    dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * Math.PI)
    dLon = (dLon * 180.0) / (a / sqrtMagic * kotlin.math.cos(radLat) * Math.PI)
    val mgLat = lat + dLat
    val mgLon = lon + dLon
    return mgLat to mgLon
}

private fun gcj02ToWgs84(lat: Double, lon: Double): Pair<Double, Double> {
    if (outOfChina(lat, lon)) return lat to lon
    val (gLat, gLon) = wgs84ToGcj02(lat, lon)
    // 反推一阶近似
    return (lat * 2 - gLat) to (lon * 2 - gLon)
}

private fun gcj02ToBd09(lat: Double, lon: Double): Pair<Double, Double> {
    val x = lon
    val y = lat
    val z = kotlin.math.sqrt(x * x + y * y) + 0.00002 * kotlin.math.sin(y * Math.PI)
    val theta = kotlin.math.atan2(y, x) + 0.000003 * kotlin.math.cos(x * Math.PI)
    val bdLon = z * kotlin.math.cos(theta) + 0.0065
    val bdLat = z * kotlin.math.sin(theta) + 0.006
    return bdLat to bdLon
}

private fun bd09ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
    val x = lon - 0.0065
    val y = lat - 0.006
    val z = kotlin.math.sqrt(x * x + y * y) - 0.00002 * kotlin.math.sin(y * Math.PI)
    val theta = kotlin.math.atan2(y, x) - 0.000003 * kotlin.math.cos(x * Math.PI)
    val ggLon = z * kotlin.math.cos(theta)
    val ggLat = z * kotlin.math.sin(theta)
    return ggLat to ggLon
}

private fun wgs84ToBd09(lat: Double, lon: Double): Pair<Double, Double> {
    val (gLat, gLon) = wgs84ToGcj02(lat, lon)
    return gcj02ToBd09(gLat, gLon)
}

/**
 * getAmapApiKey
 * 用途：从 Manifest meta-data 中读取高德 SDK 的 API Key。
 * 参数：context 应用上下文
 * 返回值：API Key 字符串或 null
 */
private fun getAmapApiKey(context: Context): String? {
    return try {
        val ai = context.packageManager.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
        ai.metaData?.getString("com.amap.api.v2.apikey")
    } catch (e: Exception) {
        null
    }
}

/**
 * bearingDirection
 * 用途：根据两点的 WGS84 坐标计算从起点指向终点的初始方位角，并映射为中文方向（八方位：北、东北、东、东南、南、西南、西、西北）。
 * 参数：
 *  - lat1 起点纬度
 *  - lon1 起点经度
 *  - lat2 终点纬度
 *  - lon2 终点经度
 * 返回值：中文方向字符串，例如 "北"、"东北"。
 */
private fun bearingDirection(lat1: Double, lon1: Double, lat2: Double, lon2: Double): String {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dLambda = Math.toRadians(lon2 - lon1)
    val y = Math.sin(dLambda) * Math.cos(phi2)
    val x = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLambda)
    var theta = Math.toDegrees(Math.atan2(y, x))
    if (theta < 0) theta += 360.0
    val dirs = arrayOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
    val idx = ((theta + 22.5) / 45.0).toInt() % 8
    return dirs[idx]
}