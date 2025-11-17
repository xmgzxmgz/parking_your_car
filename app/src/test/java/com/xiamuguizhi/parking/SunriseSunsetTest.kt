package com.xiamuguizhi.parking

import com.xiamuguizhi.parking.util.SunriseSunset
import org.junit.Assert.assertTrue
import org.junit.Test

class SunriseSunsetTest {
    @Test
    fun sunriseBeforeSunset() {
        val d = SunriseSunset.computeToday(30.0, 120.0)
        assertTrue(d.sunriseMillis < d.sunsetMillis)
    }
}