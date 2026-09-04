package com.raulsc.lenguareaccion

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Paper = Color(0xFFF7F5EF)
private val Ink = Color(0xFF173B3F)
private val Amber = Color(0xFFF1B45B)
private val Coral = Color(0xFFD86F55)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    secondary = Coral,
    tertiary = Amber,
    background = Paper,
    surface = Color(0xFFFFFDF8),
    surfaceVariant = Color(0xFFE8ECE8),
    onBackground = Color(0xFF182020),
    onSurface = Color(0xFF182020),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9ED4D0),
    secondary = Color(0xFFFFB4A1),
    tertiary = Amber,
    background = Color(0xFF101616),
    surface = Color(0xFF172020),
    onBackground = Color(0xFFE8ECE8),
    onSurface = Color(0xFFE8ECE8),
)

@Composable
fun LenguaReaccionTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}

