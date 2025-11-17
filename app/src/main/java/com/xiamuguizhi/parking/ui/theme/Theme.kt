package com.xiamuguizhi.parking.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.xiamuguizhi.parking.util.SunriseSunset

/**
 * ParkingTheme
 * 用途：根据当前时间与用户位置的日出日落自动切换明暗主题；若无定位信息则回退系统主题。
 * 参数：
 *  - latitude: 当前纬度（可空）
 *  - longitude: 当前经度（可空）
 *  - content: 组合内容
 * 返回值：无，应用 Material3 主题包裹内容。
 */
@Composable
fun ParkingTheme(latitude: Double?, longitude: Double?, overrideDark: Boolean? = null, content: @Composable () -> Unit) {
    val lightColors = lightColorScheme()
    val darkColors = darkColorScheme()

    val useDark = when {
        overrideDark != null -> overrideDark
        latitude != null && longitude != null -> {
            val now = System.currentTimeMillis()
            val sun = SunriseSunset.computeToday(latitude, longitude)
            val isNight = !sun.isDay(now)
            isNight
        }
        else -> isSystemInDarkTheme()
    }

    MaterialTheme(colorScheme = if (useDark) darkColors else lightColors) {
        content()
    }
}