package com.xiamuguizhi.parking

import android.Manifest
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView as AMapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions

/**
 * MainActivity
 * 用途：应用入口，仅展示高德地图与定位；长按可标记车位。
 * 参数：无。
 * 返回值：无。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PureAmapScreen() }
    }
}

/**
 * PureAmapScreen
 * 用途：纯高德方案地图界面。包含权限请求、一次性定位、地图显示、长按标记和缩放按钮。
 * 参数：无。
 * 返回值：无。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PureAmapScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapView by remember { mutableStateOf<AMapView?>(null) }
    var currentLatLng by remember { mutableStateOf<LatLng?>(null) }
    var markerLatLng by remember { mutableStateOf<LatLng?>(null) }

    // 运行时权限请求（定位）
    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            startAmapSingleLocation(context) { loc ->
                currentLatLng = LatLng(loc.latitude, loc.longitude)
                mapView?.map?.isMyLocationEnabled = true
                mapView?.map?.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 17f))
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("停车定位") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 地图卡片区域
            Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                Box(Modifier.height(280.dp)) {
                    AndroidView(
                        factory = { ctx ->
                            val mv = AMapView(ctx)
                            mv.onCreate(null)
                            mapView = mv
                            val aMap = mv.map
                            aMap.uiSettings.isZoomControlsEnabled = false
                            aMap.uiSettings.isMyLocationButtonEnabled = true
                            aMap.moveCamera(CameraUpdateFactory.zoomTo(17f))
                            aMap.setOnMapLongClickListener { latLng ->
                                markerLatLng = latLng
                                aMap.clear()
                                aMap.addMarker(MarkerOptions().position(latLng).title("停车位置"))
                            }
                            // 绑定生命周期，避免黑屏或资源泄漏
                            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
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
                            // 更新相机与标记
                            currentLatLng?.let { aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(it, 17f)) }
                            markerLatLng?.let { aMap.addMarker(MarkerOptions().position(it).title("停车位置")) }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // 左下角缩放按钮
                    Row(
                        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(onClick = { mapView?.map?.moveCamera(CameraUpdateFactory.zoomOut()) }) { Text("-") }
                        Button(onClick = { mapView?.map?.moveCamera(CameraUpdateFactory.zoomIn()) }) { Text("+") }
                    }
                }
            }

            Button(
                onClick = {
                    requestPermissions.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("请求定位并居中") }
        }
    }
}

/**
 * startAmapSingleLocation
 * 用途：启动一次性高德定位，成功后通过回调返回定位结果。
 * 参数：
 *  - context：上下文
 *  - onLocated：定位成功回调（AMapLocation）
 * 返回值：无。
 */
private fun startAmapSingleLocation(context: Context, onLocated: (AMapLocation) -> Unit) {
    val client = AMapLocationClient(context)
    val option = AMapLocationClientOption().apply {
        isOnceLocation = true
        locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
        isNeedAddress = false
    }
    client.setLocationOption(option)
    client.setLocationListener(object : AMapLocationListener {
        override fun onLocationChanged(amapLocation: AMapLocation?) {
            amapLocation?.let {
                if (it.errorCode == 0) {
                    onLocated(it)
                }
                client.stopLocation()
                client.onDestroy()
            }
        }
    })
    client.startLocation()
}

/**
 * getAmapApiKey
 * 用途：从 Manifest meta-data 中读取高德 SDK 的 API Key（供调试用）。
 * 参数：context 应用上下文
 * 返回值：API Key 字符串或 null
 */
private fun getAmapApiKey(context: Context): String? {
    return try {
        val ai = context.packageManager.getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
        ai.metaData?.getString("com.amap.api.v2.apikey")
    } catch (e: Exception) { null }
}