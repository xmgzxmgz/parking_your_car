package com.xiamuguizhi.parking

import com.xiamuguizhi.parking.util.SunriseSunset
import org.junit.Assert.assertTrue
import org.junit.Test

class SunriseSunsetTest {
    @Test
    fun sunriseBeforeSunset() {
        val d = SunriseSunset.computeToday(30.0, 120.0)
        assertTrue("Sunrise should be before sunset", d.sunriseMillis < d.sunsetMillis)
    }

    @Test
    fun dayDurationReasonable() {
        // 在中纬度地区，日照时间应在 8~16 小时之间
        val d = SunriseSunset.computeToday(39.9, 116.4)
        val durationHours = (d.sunsetMillis - d.sunriseMillis) / 3600_000.0
        assertTrue("Day duration should be > 8h", durationHours > 8.0)
        assertTrue("Day duration should be < 16h", durationHours < 16.0)
    }

    @Test
    fun isDayDuringNoon() {
        // 构造一个位于日出和日落之间的时刻（正午左右）
        val d = SunriseSunset.computeToday(31.2, 121.5)
        val noonish = (d.sunriseMillis + d.sunsetMillis) / 2
        assertTrue("Noon should be daytime", d.isDay(noonish))
    }

    @Test
    fun isNightBeforeSunrise() {
        val d = SunriseSunset.computeToday(31.2, 121.5)
        // 日出前1小时应为夜间
        assertTrue("Before sunrise should be night", !d.isDay(d.sunriseMillis - 3600_000L))
    }

    @Test
    fun sunriseSunsetSameDay() {
        // 赤道附近日出日落时间应都在同一天的合理范围内
        val d = SunriseSunset.computeToday(0.0, 0.0)
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = d.sunriseMillis
        val sunriseHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        cal.timeInMillis = d.sunsetMillis
        val sunsetHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        assertTrue("Sunrise should be between 5-8 AM", sunriseHour in 5..8)
        assertTrue("Sunset should be between 4-7 PM", sunsetHour in 16..19)
    }
}