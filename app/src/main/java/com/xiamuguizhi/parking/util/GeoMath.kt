package com.xiamuguizhi.parking.util

/**
 * GeoMath
 * 用途：地理计算工具集合。
 * 提供：球面距离（米）、中文八方位映射。
 */
object GeoMath {
    /**
     * 计算两点球面距离（米）。
     */
    fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
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
     * 根据 WGS84 坐标计算八方位中文文案。
     */
    fun bearingDirection(lat1: Double, lon1: Double, lat2: Double, lon2: Double): String {
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
}