package com.xiamuguizhi.parking

import com.xiamuguizhi.parking.util.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class GeoMathTest {
    // ── haversineDistanceMeters ──

    @Test
    fun distanceZero() {
        val d = GeoMath.haversineDistanceMeters(30.0, 120.0, 30.0, 120.0)
        assertTrue(d < 0.01)
    }

    @Test
    fun distanceKnown() {
        val d = GeoMath.haversineDistanceMeters(0.0, 0.0, 0.0, 1.0)
        // 赤道上经度差1度约111.32km
        assertEquals(111320.0, d, 500.0)
    }

    @Test
    fun distanceSymmetric() {
        val d1 = GeoMath.haversineDistanceMeters(39.9, 116.4, 31.2, 121.5)
        val d2 = GeoMath.haversineDistanceMeters(31.2, 121.5, 39.9, 116.4)
        assertEquals(d1, d2, 0.01)
    }

    @Test
    fun distanceAntipodal() {
        // 对跖点（地球上最远距离）约 20015km
        val d = GeoMath.haversineDistanceMeters(0.0, 0.0, 0.0, 180.0)
        assertEquals(20015086.0, d, 1000.0)
    }

    // ── bearingDirection ──

    @Test
    fun bearingEast() {
        val dir = GeoMath.bearingDirection(0.0, 0.0, 0.0, 1.0)
        assertEquals("东", dir)
    }

    @Test
    fun bearingNorth() {
        val dir = GeoMath.bearingDirection(0.0, 0.0, 1.0, 0.0)
        assertEquals("北", dir)
    }

    @Test
    fun bearingSouth() {
        val dir = GeoMath.bearingDirection(1.0, 0.0, 0.0, 0.0)
        assertEquals("南", dir)
    }

    @Test
    fun bearingWest() {
        val dir = GeoMath.bearingDirection(0.0, 1.0, 0.0, 0.0)
        assertEquals("西", dir)
    }

    // ── bearingDegrees ──

    @Test
    fun bearingDegreesEast() {
        val b = GeoMath.bearingDegrees(0.0, 0.0, 0.0, 1.0)
        assertEquals(90f, b, 1f)
    }

    @Test
    fun bearingDegreesNorth() {
        val b = GeoMath.bearingDegrees(0.0, 0.0, 1.0, 0.0)
        assertEquals(0f, b, 1f)
    }

    @Test
    fun bearingDegreesRange() {
        val b = GeoMath.bearingDegrees(31.2, 121.5, 39.9, 116.4)
        assertTrue(b in 0f..360f)
    }

    // ── normalizeAngleDegrees ──

    @Test
    fun normalizeZero() {
        assertEquals(0f, GeoMath.normalizeAngleDegrees(0f), 0.01f)
    }

    @Test
    fun normalizePositiveOver180() {
        assertEquals(-90f, GeoMath.normalizeAngleDegrees(270f), 0.01f)
    }

    @Test
    fun normalizeNegative() {
        assertEquals(-45f, GeoMath.normalizeAngleDegrees(-45f), 0.01f)
    }

    @Test
    fun normalize180() {
        assertEquals(180f, GeoMath.normalizeAngleDegrees(180f), 0.1f)
    }

    // ── outOfChina ──

    @Test
    fun shanghaiIsInChina() {
        assertFalse(GeoMath.outOfChina(31.2, 121.5))
    }

    @Test
    fun newYorkIsOutOfChina() {
        assertTrue(GeoMath.outOfChina(40.7, -74.0))
    }

    @Test
    fun londonIsOutOfChina() {
        assertTrue(GeoMath.outOfChina(51.5, -0.1))
    }

    // ── wgs84ToGcj02 / gcj02ToWgs84 ──

    @Test
    fun wgs84Gcj02Roundtrip() {
        // 转到 GCJ-02 再转回，应接近原始值（误差 < 1米）
        val lat = 31.2304
        val lon = 121.4737
        val (gLat, gLon) = GeoMath.wgs84ToGcj02(lat, lon)
        val (wLat, wLon) = GeoMath.gcj02ToWgs84(gLat, gLon)
        assertEquals(lat, wLat, 0.001)
        assertEquals(lon, wLon, 0.001)
    }

    @Test
    fun wgs84ToGcj02Shifts() {
        // 中国境内应有偏移（不是完全相同的坐标）
        val lat = 39.9042
        val lon = 116.4074
        val (gLat, gLon) = GeoMath.wgs84ToGcj02(lat, lon)
        assertTrue("Latitude should shift", kotlin.math.abs(gLat - lat) > 0.0001)
        assertTrue("Longitude should shift", kotlin.math.abs(gLon - lon) > 0.0001)
    }

    @Test
    fun wgs84ToGcj02NoShiftAbroad() {
        // 中国境外不应有偏移
        val lat = 40.7128
        val lon = -74.0060
        val (gLat, gLon) = GeoMath.wgs84ToGcj02(lat, lon)
        assertEquals(lat, gLat, 0.0001)
        assertEquals(lon, gLon, 0.0001)
    }

    // ── gcj02ToBd09 / bd09ToGcj02 ──

    @Test
    fun gcj02Bd09Roundtrip() {
        val lat = 31.2304
        val lon = 121.4737
        val (bLat, bLon) = GeoMath.gcj02ToBd09(lat, lon)
        val (gLat, gLon) = GeoMath.bd09ToGcj02(bLat, bLon)
        assertEquals(lat, gLat, 0.001)
        assertEquals(lon, gLon, 0.001)
    }

    @Test
    fun bd09ShiftsFromGcj02() {
        // BD-09 在 GCJ-02 基础上有固定偏移
        val lat = 39.9042
        val lon = 116.4074
        val (bLat, bLon) = GeoMath.gcj02ToBd09(lat, lon)
        assertTrue("BD09 lat should differ", kotlin.math.abs(bLat - lat) > 0.001)
        assertTrue("BD09 lon should differ", kotlin.math.abs(bLon - lon) > 0.001)
    }

    // ── wgs84ToBd09 ──

    @Test
    fun wgs84ToBd09Shifts() {
        val lat = 39.9042
        val lon = 116.4074
        val (bLat, bLon) = GeoMath.wgs84ToBd09(lat, lon)
        // Should differ from both WGS84 and GCJ02
        assertTrue(kotlin.math.abs(bLat - lat) > 0.001)
        assertTrue(kotlin.math.abs(bLon - lon) > 0.001)
    }
}
