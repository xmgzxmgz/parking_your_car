package com.xiamuguizhi.parking.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

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

        // 历史记录（最近若干条）与日志（简易事件日志）的 JSON 序列化存储键
        private val KEY_HISTORY = stringPreferencesKey("history_json")
        private val KEY_LOGS = stringPreferencesKey("logs_json")
        private const val HISTORY_LIMIT = 10
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
     * LogEntry 数据模型
     * @property timestamp 毫秒时间戳
     * @property message 文本消息
     */
    data class LogEntry(val timestamp: Long, val message: String)

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
     * 读取最近保存的停车历史列表（按时间倒序，最多 HISTORY_LIMIT 条）
     * @return Flow<List<ParkingRecord>> 历史记录列表
     */
    val historyFlow: Flow<List<ParkingRecord>> = context.parkingDataStore.data.map { prefs ->
        val json = prefs[KEY_HISTORY]
        if (json.isNullOrBlank()) emptyList() else json.split("\n").mapNotNull { line ->
            val parts = line.split("|::|")
            // 格式：ts|::|lat|::|lon|::|floor|::|spot|::|address|::|photos(joined with ^)
            if (parts.size >= 7) {
                val ts = parts[0].toLongOrNull() ?: return@mapNotNull null
                val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                val lon = parts[2].toDoubleOrNull() ?: return@mapNotNull null
                val floor = parts[3].ifBlank { null }
                val spot = parts[4].ifBlank { null }
                val address = parts[5].ifBlank { null }
                val photos = parts[6].takeIf { it.isNotBlank() }?.split('^') ?: emptyList()
                ParkingRecord(lat, lon, floor, spot, photos, address, ts)
            } else null
        }
    }

    /**
     * 读取简易事件日志
     * @return Flow<List<LogEntry>> 按时间倒序
     */
    val logsFlow: Flow<List<LogEntry>> = context.parkingDataStore.data.map { prefs ->
        val json = prefs[KEY_LOGS]
        if (json.isNullOrBlank()) emptyList() else json.split("\n").mapNotNull { line ->
            val p = line.split("|::|")
            if (p.size >= 2) LogEntry(p[0].toLongOrNull() ?: return@mapNotNull null, p[1]) else null
        }
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
        context.parkingDataStore.edit { prefs: MutablePreferences ->
            prefs[KEY_LAT] = lat
            prefs[KEY_LON] = lon
            prefs[KEY_FLOOR] = floor ?: ""
            prefs[KEY_SPOT] = spot ?: ""
            prefs[KEY_ADDRESS] = address ?: ""
            prefs[KEY_TIMESTAMP] = timestamp
            if (photoUris != null) {
                prefs[KEY_PHOTOS] = photoUris.joinToString(separator = "|::|")
            }

            // 追加到历史列表（行序列化，最新在顶部）
            val photosJoined = (photoUris ?: (prefs[KEY_PHOTOS]?.split("|::|") ?: emptyList()))
                .joinToString(separator = "^")
            val line = listOf(
                timestamp.toString(),
                lat.toString(),
                lon.toString(),
                (floor ?: ""),
                (spot ?: ""),
                (address ?: ""),
                photosJoined
            ).joinToString(separator = "|::|")
            val old = prefs[KEY_HISTORY]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
            val new = (listOf(line) + old).take(HISTORY_LIMIT)
            prefs[KEY_HISTORY] = new.joinToString(separator = "\n")

            // 写日志：保存记录
            appendLogNoSuspend(prefs, "保存停车记录：spot=${spot ?: ""}" )
        }
    }

    /**
     * 添加一张照片的 Uri 到现有列表
     * @param uri 新增照片的 Uri 字符串
     */
    suspend fun appendPhoto(uri: String) {
        context.parkingDataStore.edit { prefs: MutablePreferences ->
            val photosJson = prefs[KEY_PHOTOS]
            val list = photosJson?.takeIf { it.isNotBlank() }?.split("|::|")?.toMutableList() ?: mutableListOf()
            list.add(uri)
            prefs[KEY_PHOTOS] = list.joinToString(separator = "|::|")

            // 写日志：添加照片
            appendLogNoSuspend(prefs, "添加照片：$uri")
        }
    }

    /**
     * 清除当前停车记录
     */
    suspend fun clear() {
        context.parkingDataStore.edit { prefs: MutablePreferences ->
            prefs.clear()
            // 写日志：清除记录
            appendLogNoSuspend(prefs, "清除停车记录")
        }
    }

    /**
     * 追加一条日志（挂起外包装）
     * @param message 日志文本
     */
    suspend fun appendLog(message: String) {
        context.parkingDataStore.edit { prefs: MutablePreferences ->
            appendLogNoSuspend(prefs, message)
        }
    }

    /**
     * 追加日志（非挂起内部实现，便于在 edit 中复用）
     */
    private fun appendLogNoSuspend(prefs: MutablePreferences, message: String) {
        val now = System.currentTimeMillis()
        val line = listOf(now.toString(), message).joinToString(separator = "|::|")
        val old = prefs[KEY_LOGS]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        val new = (listOf(line) + old).take(100)
        prefs[KEY_LOGS] = new.joinToString(separator = "\n")
    }

    /**
     * 导出当前数据为 JSON 字符串（简单结构）。
     * 包含：当前记录、历史、日志。
     */
    suspend fun exportJson(): String {
        val prefs = context.parkingDataStore.data.first()
        val cur = run {
            val lat = prefs[KEY_LAT]
            val lon = prefs[KEY_LON]
            val ts = prefs[KEY_TIMESTAMP]
            if (lat == null || lon == null || ts == null) null else mapOf(
                "lat" to lat,
                "lon" to lon,
                "floor" to (prefs[KEY_FLOOR] ?: ""),
                "spot" to (prefs[KEY_SPOT] ?: ""),
                "address" to (prefs[KEY_ADDRESS] ?: ""),
                "timestamp" to ts,
                "photos" to (prefs[KEY_PHOTOS] ?: "")
            )
        }
        val history = prefs[KEY_HISTORY] ?: ""
        val logs = prefs[KEY_LOGS] ?: ""
        val map = mapOf(
            "current" to cur,
            "history" to history,
            "logs" to logs
        )
        return map.entries.joinToString(separator = "\n") { (k, v) -> "$k=$v" }
    }

    /**
     * 从 JSON 字符串导入数据（覆盖当前记录与历史日志）。
     */
    suspend fun importJson(json: String) {
        val lines = json.lines()
        val kv = lines.mapNotNull {
            val i = it.indexOf('=')
            if (i > 0) it.substring(0, i) to it.substring(i + 1) else null
        }.toMap()
        val cur = kv["current"]
        val hist = kv["history"]
        val logs = kv["logs"]
        context.parkingDataStore.edit { prefs: MutablePreferences ->
            hist?.let { prefs[KEY_HISTORY] = it }
            logs?.let { prefs[KEY_LOGS] = it }
            if (!cur.isNullOrBlank()) {
                // 解析类似 {lat=..,lon=..,floor=..,...}
                val parts = cur.removePrefix("{").removeSuffix("}")
                    .split(',').map { it.trim() }
                val map = parts.mapNotNull { p ->
                    val idx = p.indexOf('='); if (idx > 0) p.substring(0, idx) to p.substring(idx + 1) else null
                }.toMap()
                val lat = map["lat"]?.toDoubleOrNull()
                val lon = map["lon"]?.toDoubleOrNull()
                val ts = map["timestamp"]?.toLongOrNull()
                if (lat != null && lon != null && ts != null) {
                    prefs[KEY_LAT] = lat
                    prefs[KEY_LON] = lon
                    prefs[KEY_TIMESTAMP] = ts
                    prefs[KEY_FLOOR] = map["floor"] ?: ""
                    prefs[KEY_SPOT] = map["spot"] ?: ""
                    prefs[KEY_ADDRESS] = map["address"] ?: ""
                    prefs[KEY_PHOTOS] = map["photos"] ?: ""
                }
            }
        }
    }
}