package com.example.talkingalarm

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NightScheme = darkColorScheme(
    primary = Color(0xFFF2C14E),
    onPrimary = Color(0xFF241B00),
    primaryContainer = Color(0xFF3A2E08),
    onPrimaryContainer = Color(0xFFFFE4A3),
    secondary = Color(0xFF8FA6D0),
    background = Color(0xFF0E1220),
    onBackground = Color(0xFFE8EBF4),
    surface = Color(0xFF161C30),
    onSurface = Color(0xFFE8EBF4),
    surfaceVariant = Color(0xFF1E2740),
    onSurfaceVariant = Color(0xFFB4BDD4),
    outline = Color(0xFF48546F),
    error = Color(0xFFE0715F)
)

@Composable
fun TalkingAlarmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NightScheme, content = content)
}
