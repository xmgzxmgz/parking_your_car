package com.xiamuguizhi.parking.util

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*

/**
 * SunriseSunset
 * 用途：计算当日的日出日落时间（简化 NOAA 算法）。
 * 参数：使用 [computeToday] 输入纬度与经度。
 * 返回值：提供 [sunriseMillis] 与 [sunsetMillis]，并提供 [isDay] 判断某时刻是否为白天。
 */
object SunriseSunset {
    data class DayTimes(val sunriseMillis: Long, val sunsetMillis: Long) {
        fun isDay(nowMillis: Long): Boolean = nowMillis in sunriseMillis..sunsetMillis
    }

    /**
     * 计算当日日出日落（近似算法）。
     * @param lat 纬度
     * @param lon 经度
     * @return DayTimes
     */
    fun computeToday(lat: Double, lon: Double): DayTimes {
        val tz = TimeZone.getDefault()
        val cal = Calendar.getInstance(tz)
        val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)

        // 估算均时差与赤纬（近似）
        val gamma = 2.0 * Math.PI / 365.0 * (dayOfYear - 1 + ((cal.get(Calendar.HOUR_OF_DAY) - 12) / 24.0))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) - 0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)

        val latRad = Math.toRadians(lat)
        val cosH = (cos(Math.toRadians(90.833)) - sin(latRad) * sin(decl)) / (cos(latRad) * cos(decl))
        val H = acos(cosH)

        val lngHour = lon / 15.0
        val sunriseTime = 12.0 - Math.toDegrees(H) / 15.0 - lngHour
        val sunsetTime = 12.0 + Math.toDegrees(H) / 15.0 - lngHour

        fun toMillis(hoursLocal: Double): Long {
            val h = floor(hoursLocal).toInt()
            val m = floor((hoursLocal - h) * 60.0).toInt()
            val s = floor(((hoursLocal - h) * 60.0 - m) * 60.0).toInt()
            val c = Calendar.getInstance(tz).apply {
                set(Calendar.HOUR_OF_DAY, h)
                set(Calendar.MINUTE, m)
                set(Calendar.SECOND, s)
                set(Calendar.MILLISECOND, 0)
            }
            return c.timeInMillis
        }

        val sunriseMillis = toMillis(sunriseTime)
        val sunsetMillis = toMillis(sunsetTime)
        return DayTimes(sunriseMillis, sunsetMillis)
    }
}