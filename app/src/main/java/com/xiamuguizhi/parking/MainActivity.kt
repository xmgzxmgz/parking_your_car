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
import com.xiamuguizhi.parking.data.ParkingDataStore
import com.xiamuguizhi.parking.ui.theme.ParkingTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (record == null) {
                var floor by remember { mutableStateOf<String?>(null) }
                var spot by remember { mutableStateOf<String?>(null) }

                Text(text = "您尚未标记停车位")
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
                    Text("标记停车位")
                }
            } else {
                // 展示记录信息
                Text(text = "记录时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date(record.timestamp))}")
                Text(text = "纬度：${record.lat}")
                Text(text = "经度：${record.lon}")
                record.address?.takeIf { it.isNotBlank() }?.let { Text(text = "地址：$it") }
                record.floor?.takeIf { it.isNotBlank() }?.let { Text(text = "楼层：$it") }
                record.spot?.takeIf { it.isNotBlank() }?.let { Text(text = "车位号：$it") }

                // 照片区域
                Text(text = "已添加的照片：")
                if (record.photoUris.isEmpty()) {
                    Text(text = "暂无照片")
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(record.photoUris) { uri ->
                            Image(
                                painter = rememberAsyncImagePainter(model = uri),
                                contentDescription = null,
                                modifier = Modifier.size(100.dp),
                                contentScale = ContentScale.Crop
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