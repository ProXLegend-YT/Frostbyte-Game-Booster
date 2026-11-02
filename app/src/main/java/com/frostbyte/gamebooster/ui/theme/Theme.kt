package com.frostbyte.gamebooster.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Frost {
    var Bg = Color(0xFF050B16)
        private set
    var Card = Color(0xFF0C1626)
        private set
    val CardEdge = Color(0xFF16304A)
    val Cyan = Color(0xFF38D0FF)
    val Blue = Color(0xFF1E88FF)
    val Text = Color(0xFFEAF6FF)
    val Muted = Color(0xFF8DA6BD)
    val Good = Color(0xFF3DDC84)
    val Warm = Color(0xFFFFC94D)
    val Hot = Color(0xFFFF8A3D)
    val Critical = Color(0xFFFF4D5E)

    /** True-black mode for OLED screens. Call before setContent. */
    fun applyAmoled(on: Boolean) {
        Bg = if (on) Color.Black else Color(0xFF050B16)
        Card = if (on) Color(0xFF07090D) else Color(0xFF0C1626)
    }
}

@Composable
fun FrostByteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Frost.Cyan,
            onPrimary = Frost.Bg,
            background = Frost.Bg,
            onBackground = Frost.Text,
            surface = Frost.Card,
            onSurface = Frost.Text,
        ),
        content = content,
    )
}
