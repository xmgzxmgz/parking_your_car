package com.xiamuguizhi.parking.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * ParkingDataStore
 * 用途：管理停车记录的持久化存储（位置、楼层、车位号、照片 URIs、地址、时间）。
 * 参数：构造需要 [Context] 获取 DataStore。
 * 返回值：提供 [Flow] 以读取当前记录，以及保存/清除的挂起方法。
 */
class ParkingDataStore(private val context: Context) {

    companion object {
        private val Context.parkingDataStore by preferencesDataStore(name = "parking_data")

        private val KEY_LAT = doublePreferencesKey("lat")
        private val KEY_LON = doublePreferencesKey("lon")
        private val KEY_FLOOR = stringPreferencesKey("floor")
        private val KEY_SPOT = stringPreferencesKey("spot")
        private val KEY_PHOTOS = stringPreferencesKey("photos_json")
        private val KEY_ADDRESS = stringPreferencesKey("address")
        private val KEY_TIMESTAMP = longPreferencesKey("timestamp")
    }

    /**
     * ParkingRecord 数据模型
     * @property lat 纬度
     * @property lon 经度
     * @property floor 楼层（如“地下一层”等）
     * @property spot 车位号（如“457”）
     * @property photoUris 照片 Uri 列表
     * @property address 地址（可选，地理反向编码）
     * @property timestamp 记录时间毫秒
     */
    data class ParkingRecord(
        val lat: Double,
        val lon: Double,
        val floor: String?,
        val spot: String?,
        val photoUris: List<String>,
        val address: String?,
        val timestamp: Long
    )

    /**
     * 读取当前保存的停车记录
     * @return Flow<ParkingRecord?> 可为空（无记录时）
     */
    val recordFlow: Flow<ParkingRecord?> = context.parkingDataStore.data.map { prefs ->
        val lat = prefs[KEY_LAT]
        val lon = prefs[KEY_LON]
        val ts = prefs[KEY_TIMESTAMP]
        if (lat == null || lon == null || ts == null) return@map null
        val floor = prefs[KEY_FLOOR]
        val spot = prefs[KEY_SPOT]
        val address = prefs[KEY_ADDRESS]
        val photosJson = prefs[KEY_PHOTOS]
        val photos = photosJson?.takeIf { it.isNotBlank() }?.split("|::|") ?: emptyList()
        ParkingRecord(lat, lon, floor, spot, photos, address, ts)
    }

    /**
     * 保存停车记录（不覆盖照片，除非传入对应照片列表）
     * @param lat 纬度
     * @param lon 经度
     * @param floor 楼层
     * @param spot 车位号
     * @param address 地址（可选）
     * @param timestamp 记录时间（毫秒）
     * @param photoUris 照片列表（可选）。传 null 则保持现有列表不变。
     */
    suspend fun saveRecord(
        lat: Double,
        lon: Double,
        floor: String?,
        spot: String?,
        address: String?,
        timestamp: Long,
        photoUris: List<String>? = null
    ) {
        context.parkingDataStore.edit { prefs: Preferences ->
            prefs[KEY_LAT] = lat
            prefs[KEY_LON] = lon
            prefs[KEY_FLOOR] = floor ?: ""
            prefs[KEY_SPOT] = spot ?: ""
            prefs[KEY_ADDRESS] = address ?: ""
            prefs[KEY_TIMESTAMP] = timestamp
            if (photoUris != null) {
                prefs[KEY_PHOTOS] = photoUris.joinToString(separator = "|::|")
            }
        }
    }

    /**
     * 添加一张照片的 Uri 到现有列表
     * @param uri 新增照片的 Uri 字符串
     */
    suspend fun appendPhoto(uri: String) {
        context.parkingDataStore.edit { prefs ->
            val photosJson = prefs[KEY_PHOTOS]
            val list = photosJson?.takeIf { it.isNotBlank() }?.split("|::|")?.toMutableList() ?: mutableListOf()
            list.add(uri)
            prefs[KEY_PHOTOS] = list.joinToString(separator = "|::|")
        }
    }

    /**
     * 清除当前停车记录
     */
    suspend fun clear() {
        context.parkingDataStore.edit { prefs ->
            prefs.clear()
        }
    }
}