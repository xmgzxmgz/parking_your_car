package com.xiamuguizhi.parking

import com.xiamuguizhi.parking.util.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {
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
    fun bearingEast() {
        val dir = GeoMath.bearingDirection(0.0, 0.0, 0.0, 1.0)
        assertEquals("东", dir)
    }
}