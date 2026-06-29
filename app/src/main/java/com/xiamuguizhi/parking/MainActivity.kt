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
import com.xiamuguizhi.parking.util.GeoMath

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
            var themeOverride by remember { mutableStateOf<Boolean?>(null) }

            ParkingTheme(latitude = themeLat, longitude = themeLon, overrideDark = themeOverride) {
                MainScreen(
                    dataStore = dataStore,
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

    /**
     * 打开外部地图进行导航（geo: URI），若无可用应用则无动作。
     */
    private fun navigateExternal(lat: Double, lon: Double) {
        try {
            val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(停车位置)")
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {}
    }

    /**
     * 分享停车信息到其它应用
     */
    private fun shareParking(record: ParkingDataStore.ParkingRecord) {
        val text = buildString {
            append("停车位置：${record.lat}, ${record.lon}\n")
            record.spot?.takeIf { it.isNotBlank() }?.let { append("车位号：$it\n") }
            record.floor?.takeIf { it.isNotBlank() }?.let { append("楼层：$it\n") }
            record.address?.takeIf { it.isNotBlank() }?.let { append("地址：$it\n") }
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        startActivity(android.content.Intent.createChooser(intent, "分享停车信息"))
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
    dataStore: ParkingDataStore,
    record: ParkingDataStore.ParkingRecord?,
    requestLocationAndSave: (String?, String?) -> Unit,
    appendPhoto: (Uri) -> Unit,
    clearRecord: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var showAR by remember { mutableStateOf(false) }
    var showReminder by remember { mutableStateOf(false) }

    // 权限请求器
    val snackbarHostState = remember { SnackbarHostState() }
    var permissionsGranted by remember { mutableStateOf(false) }
    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        ).all { p -> result[p] == true }
        if (!permissionsGranted) {
            scope.launch {
                snackbarHostState.showSnackbar(stringResource(id = R.string.snack_perm_denied))
            }
        }
    }

    // 拍照：使用 MediaStore 创建图片并拍摄
    val context = LocalContext.current
    var pendingPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) pendingPhotoUri?.let { appendPhoto(it) }
    }
    // 导入 JSON 文件
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    if (!text.isNullOrBlank()) {
                        dataStore.importJson(text)
                        snackbarHostState.showSnackbar("导入成功")
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("导入失败")
                }
            }
        }
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
                title = { Text(text = stringResource(id = R.string.title_main)) },
                navigationIcon = {
                    if (record != null) {
                        TextButton(onClick = clearRecord) { Text(text = stringResource(id = R.string.action_clear)) }
                    }
                }
                , actions = {
                    // 简单主题切换：跟随系统/浅色/深色 循环
                    TextButton(onClick = {
                        themeOverride = when (themeOverride) {
                            null -> false
                            false -> true
                            true -> null
                        }
                    }) {
                        Text(
                            when (themeOverride) {
                                null -> stringResource(id = R.string.theme_system)
                                false -> stringResource(id = R.string.theme_light)
                                true -> stringResource(id = R.string.theme_dark)
                            }
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
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
                        Text(text = stringResource(id = R.string.hint_spot_first))
                        FloorSelector(selected = floor, onSelect = { floor = it })
                        OutlinedTextField(
                            value = spot ?: "",
                            onValueChange = { spot = it },
                            label = { Text(stringResource(id = R.string.spot_label)) }
                        )
                        Button(onClick = {
                            // 请求定位与摄像头权限
                            requestPermissions.launch(arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.CAMERA
                            ))
                            if (permissionsGranted) {
                                requestLocationAndSave(floor, spot)
                            } else {
                                scope.launch { snackbarHostState.showSnackbar(stringResource(id = R.string.snack_perm_needed)) }
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(id = R.string.action_save_detail))
                        }
                    }
                }
            } else {
                // 展示记录信息（卡片）
                Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = "${stringResource(id = R.string.saved_at)}：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(record.timestamp))}")
                        record.spot?.takeIf { it.isNotBlank() }?.let { Text(text = "车位号：$it") }
                        Text(text = "停车位置：${record.lat}, ${record.lon}")
                        // 实时距离（米）
                        val cur = currentLocationState.value
                        val distanceMeters = remember(cur, record) {
                            cur?.let { d -> GeoMath.haversineDistanceMeters(d.first, d.second, record.lat, record.lon) }
                        }
                        distanceMeters?.let { Text(text = "${stringResource(id = R.string.label_distance)}${"%.1f".format(it)} 米") }

                        // 方向提示：从当前位置前往停车位置应朝向
                        val directionText = remember(cur, record) {
                            cur?.let { d -> GeoMath.bearingDirection(d.first, d.second, record.lat, record.lon) }
                        }
                        directionText?.let { Text(text = "${stringResource(id = R.string.label_direction)}$it") }

                        // 照片区域
                        Text(text = stringResource(id = R.string.label_photos))
                        if (record.photoUris.isEmpty()) {
                            Text(text = stringResource(id = R.string.label_no_photos))
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
                    }) { Text(stringResource(id = R.string.action_take_photo)) }

                    TextButton(onClick = { showAR = true }) { Text(stringResource(id = R.string.action_enter_ar)) }

                    // 外部导航
                    TextButton(onClick = {
                        record?.let { navigateExternal(it.lat, it.lon) }
                    }) { Text(stringResource(id = R.string.action_open_nav)) }

                    // 分享
                    TextButton(onClick = {
                        record?.let { (context as MainActivity).shareParking(it) }
                    }) { Text(stringResource(id = R.string.action_share)) }

                    // 导出
                    TextButton(onClick = {
                        scope.launch {
                            val json = dataStore.exportJson()
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, json)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, stringResource(id = R.string.action_export_title)))
                        }
                    }) { Text(stringResource(id = R.string.action_export)) }

                    // 导入
                    TextButton(onClick = { importLauncher.launch("text/*") }) { Text(stringResource(id = R.string.action_import)) }

                    // 计时提醒
                    TextButton(onClick = { showReminder = true }) { Text(stringResource(id = R.string.action_set_reminder)) }

                    // 历史记录
                    var showHistory by remember { mutableStateOf(false) }
                    TextButton(onClick = { showHistory = true }) { Text("历史记录") }
                    if (showHistory) {
                        Dialog(onDismissRequest = { showHistory = false }) {
                            HistoryList(
                                history = dataStore.historyFlow.collectAsState(initial = emptyList()).value,
                                onUse = { rec ->
                                    // 将选中历史设置为当前记录
                                    scope.launch {
                                        dataStore.saveRecord(
                                            lat = rec.lat,
                                            lon = rec.lon,
                                            floor = rec.floor,
                                            spot = rec.spot,
                                            address = rec.address,
                                            timestamp = System.currentTimeMillis(),
                                            photoUris = rec.photoUris
                                        )
                                        showHistory = false
                                    }
                                }
                            )
                        }
                    }
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
                            ) { Text(stringResource(id = R.string.dialog_close)) }
                        }
                    }
                }

                if (showReminder) {
                    Dialog(onDismissRequest = { showReminder = false }) {
                        Card(shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("选择提醒时间：")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(30, 60, 120).forEach { min ->
                                        Button(onClick = {
                                            record?.let {
                                                scheduleReminder(context, min)
                                                scope.launch { snackbarHostState.showSnackbar("已设置${min}分钟提醒") }
                                            }
                                            showReminder = false
                                        }) { Text("${min}分钟") }
                                    }
                                }
                            }
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
 * HistoryList
 * 用途：展示最近若干条历史记录，支持一键使用该记录。
 * 参数：
 *  - history 历史列表
 *  - onUse 选择使用某条历史的回调
 */
@Composable
fun HistoryList(history: List<ParkingDataStore.ParkingRecord>, onUse: (ParkingDataStore.ParkingRecord) -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(id = R.string.label_history_title))
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history.size) { idx ->
                val rec = history[idx]
                Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = "${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(rec.timestamp))}")
                        rec.spot?.takeIf { it.isNotBlank() }?.let { Text(text = "车位：$it") }
                        rec.floor?.takeIf { it.isNotBlank() }?.let { Text(text = "楼层：$it") }
                        Text(text = "坐标：${"%.5f".format(rec.lat)}, ${"%.5f".format(rec.lon)}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onUse(rec) }) { Text(stringResource(id = R.string.action_used_record)) }
                        }
                    }
                }
            }
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
                    val (wLat, wLon) = GeoMath.gcj02ToWgs84(ll.latitude, ll.longitude)
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
                    val (gLat, gLon) = GeoMath.wgs84ToGcj02(lat, lon)
                    val ll = LatLng(gLat, gLon)
                    aMap.addMarker(MarkerOptions().position(ll).title("当前位置"))
                    center = ll
                }
                parking?.let { (lat, lon) ->
                    val (gLat, gLon) = GeoMath.wgs84ToGcj02(lat, lon)
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
                            val (wgsLat, wgsLon) = GeoMath.gcj02ToWgs84(p.latitude, p.longitude)
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
                    val (gcjLatCur, gcjLonCur) = GeoMath.wgs84ToGcj02(lat, lon)
                    val gp = GeoPoint(gcjLatCur, gcjLonCur)
                    val marker = Marker(map).apply { position = gp; title = "当前位置" }
                    map.overlays.add(marker)
                    center = gp
                }
                parking?.let { (lat, lon) ->
                    val (gcjLatPk, gcjLonPk) = GeoMath.wgs84ToGcj02(lat, lon)
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
 * scheduleReminder
 * 用途：通过 AlarmManager 设置精确计时提醒。
 * @param context 应用上下文
 * @param minutes 提醒时间（分钟）
 */
private fun scheduleReminder(context: Context, minutes: Int) {
    try {
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L
        val intent = android.content.Intent(context, com.xiamuguizhi.parking.notify.ReminderReceiver::class.java).apply {
            putExtra("title", "停车计时到期")
            putExtra("text", "您设置的停车计时(${minutes}分钟)已到")
        }
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (android.os.Build.VERSION.SDK_INT >= 23) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
        val pi = android.app.PendingIntent.getBroadcast(context, 1001, intent, flags)
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
    } catch (_: Exception) {
        // AlarmManager 不可用时静默失败
    }
}