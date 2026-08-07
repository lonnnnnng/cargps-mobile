package com.cargps.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MobileDarkColors = darkColorScheme(
    primary = Color(0xFFF97316),
    onPrimary = Color(0xFF2B1000),
    primaryContainer = Color(0xFF542200),
    onPrimaryContainer = Color(0xFFFFDBC8),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF002116),
    background = Color(0xFF0B101B),
    surface = Color(0xFF121927),
    surfaceVariant = Color(0xFF182131),
    onBackground = Color(0xFFF7F8FC),
    onSurface = Color(0xFFF7F8FC),
    outline = Color(0xFF344155),
    error = Color(0xFFFF6B6B),
)

private val MobileLightColors = lightColorScheme(
    primary = Color(0xFFC2410C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC8),
    onPrimaryContainer = Color(0xFF3A1000),
    secondary = Color(0xFF047857),
    onSecondary = Color.White,
    background = Color(0xFFF4F6FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFE9EEF5),
    onBackground = Color(0xFF151A23),
    onSurface = Color(0xFF151A23),
    outline = Color(0xFFBCC5D2),
    error = Color(0xFFB42318),
)

@Composable
fun MobileGpsTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) MobileDarkColors else MobileLightColors,
        typography = Typography(),
        content = content,
    )
}
