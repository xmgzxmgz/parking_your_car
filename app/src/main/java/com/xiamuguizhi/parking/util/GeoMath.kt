package com.xiamuguizhi.parking.util

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GeoMath
 * 用途：地理计算工具集合。
 * 提供：球面距离（米）、中文八方位映射、坐标系转换（WGS84/GCJ02/BD09）。
 *
 * - WGS84：GPS 全球标准坐标系
 * - GCJ02：中国国家测绘局偏移坐标系（高德、腾讯地图使用）
 * - BD09：百度坐标系（在 GCJ02 基础上再次偏移）
 */
object GeoMath {

    /** GCJ02 椭球长半轴（米） */
    private const val SEMI_MAJOR_AXIS = 6378245.0
    /** GCJ02 椭球第一偏心率平方 */
    private const val ECCENTRICITY_SQ = 0.00669342162296594323
    /** 地球平均半径（米），用于 haversine 计算 */
    private const val EARTH_RADIUS_M = 6371000.0
    /** BD09 坐标偏移常量 */
    private const val BD_OFFSET_LAT = 0.006
    private const val BD_OFFSET_LON = 0.0065
    private const val BD_OFFSET_FACTOR = 0.00002
    private const val BD_OFFSET_ANGLE = 0.000003

    /**
     * 计算两点球面距离（Haversine 公式）。
     * @return 距离（米）
     */
    fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    /**
     * 根据 WGS84 坐标计算八方位中文文案。
     * 方位角按 45 度分段映射：北、东北、东、东南、南、西南、西、西北。
     */
    fun bearingDirection(lat1: Double, lon1: Double, lat2: Double, lon2: Double): String {
        val bearing = bearingDegrees(lat1, lon1, lat2, lon2)
        val dirs = arrayOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        val idx = ((bearing + 22.5) / 45.0).toInt() % 8
        return dirs[idx]
    }

    /**
     * 计算从点 A 指向点 B 的方位角（0~360，0=北，顺时针）。
     */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
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
     * 将角度归一化到 [-180, 180] 区间。
     */
    fun normalizeAngleDegrees(a: Float): Float {
        var v = ((a + 180f) % 360f)
        if (v < 0f) v += 360f
        return v - 180f
    }

    // ──────────────────────────────────────────────
    //  坐标系转换：WGS84 ↔ GCJ02 ↔ BD09
    //  实现参考中国国家测绘局公开的偏移算法。
    // ──────────────────────────────────────────────

    /**
     * 判断坐标是否在中国境外（中国坐标偏移仅对境内有效）。
     */
    fun outOfChina(lat: Double, lon: Double): Boolean {
        return lon !in 72.004..137.8347 || lat !in 0.8293..55.8271
    }

    /**
     * WGS84 转 GCJ02（国内底图坐标）。
     * 中国境外坐标不做偏移直接返回。
     */
    fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - ECCENTRICITY_SQ * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((SEMI_MAJOR_AXIS * (1 - ECCENTRICITY_SQ)) / (magic * sqrtMagic) * Math.PI)
        dLon = (dLon * 180.0) / (SEMI_MAJOR_AXIS / sqrtMagic * cos(radLat) * Math.PI)
        return (lat + dLat) to (lon + dLon)
    }

    /**
     * GCJ02 转 WGS84（GPS 坐标）。
     * 使用一阶近似反推，精度约 1-2 米，满足寻车需求。
     */
    fun gcj02ToWgs84(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) return lat to lon
        val (gLat, gLon) = wgs84ToGcj02(lat, lon)
        return (lat * 2 - gLat) to (lon * 2 - gLon)
    }

    /** GCJ02 转 BD09（百度坐标系）。 */
    fun gcj02ToBd09(lat: Double, lon: Double): Pair<Double, Double> {
        val x = lon
        val y = lat
        val z = sqrt(x * x + y * y) + BD_OFFSET_FACTOR * sin(y * Math.PI)
        val theta = atan2(y, x) + BD_OFFSET_ANGLE * cos(x * Math.PI)
        return (z * sin(theta) + BD_OFFSET_LAT) to (z * cos(theta) + BD_OFFSET_LON)
    }

    /** BD09 转 GCJ02。 */
    fun bd09ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        val x = lon - BD_OFFSET_LON
        val y = lat - BD_OFFSET_LAT
        val z = sqrt(x * x + y * y) - BD_OFFSET_FACTOR * sin(y * Math.PI)
        val theta = atan2(y, x) - BD_OFFSET_ANGLE * cos(x * Math.PI)
        return (z * sin(theta)) to (z * cos(theta))
    }

    /** WGS84 转 BD09（经由 GCJ02 中转）。 */
    fun wgs84ToBd09(lat: Double, lon: Double): Pair<Double, Double> {
        val (gLat, gLon) = wgs84ToGcj02(lat, lon)
        return gcj02ToBd09(gLat, gLon)
    }

    // ── 内部变换函数（NOAA 标准偏移算法） ──

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}