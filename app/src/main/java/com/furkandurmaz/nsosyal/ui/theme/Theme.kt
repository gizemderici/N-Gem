package com.furkandurmaz.nsosyal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Canvas = Color(0xFFFBFBF7)
val Surface = Color(0xFFFFFFFF)
val ElevatedSurface = Color(0xFFF3F4EE)
val Ink = Color(0xFF131417)
val MutedInk = Color(0xFF64666D)
val SubtleInk = Color(0xFF96999F)
val Border = Color(0x14000000)
val StrongBorder = Color(0x22000000)
val Cyan = Color(0xFF14C4E0)
val Blue = Color(0xFF3878FA)
val Violet = Color(0xFF7D47EF)
val Amber = Color(0xFFF7AD1F)
val Green = Color(0xFF21AB73)
val Coral = Color(0xFFF25A5A)
val DeepBlue = Color(0xFF0E1839)

val BrandBrush = Brush.linearGradient(listOf(Cyan, Blue, Violet))
val DarkBrush = Brush.linearGradient(listOf(Color(0xFF0D1324), Color(0xFF171F40)))

private val NSosyalColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    secondary = Blue,
    onSecondary = Color.White,
    background = Canvas,
    onBackground = Ink,
    surface = Surface,
    onSurface = Ink,
    surfaceVariant = ElevatedSurface,
    onSurfaceVariant = MutedInk,
    outline = Border,
    error = Coral
)

@Composable
fun NSosyalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NSosyalColors,
        content = content
    )
}
