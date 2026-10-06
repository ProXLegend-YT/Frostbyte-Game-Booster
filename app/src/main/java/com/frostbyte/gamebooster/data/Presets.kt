package com.frostbyte.gamebooster.data

import android.content.Context

data class PanelPreset(
    val id: String,
    val label: String,
    val accent: Int,
    val bg: Int,
    val card: Int,
    val opacity: Float,
    val cards: Set<String>,
    val shortcuts: Set<String>,
)

object PanelPresets {
    val all = listOf(
        PanelPreset("frost", "Frost", 0xFF38D0FF.toInt(), 0xFF060C18.toInt(), 0xFF122238.toInt(), 0.78f, Prefs.ALL_CARDS, Prefs.ALL_SHORTCUTS),
        PanelPreset("dark", "Dark", 0xFFB0BEC5.toInt(), 0xFF101012.toInt(), 0xFF1C1C20.toInt(), 0.80f, Prefs.ALL_CARDS, Prefs.ALL_SHORTCUTS),
        PanelPreset("amoled", "AMOLED", 0xFF38D0FF.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0.85f, Prefs.ALL_CARDS, Prefs.ALL_SHORTCUTS),
        PanelPreset("minimal", "Minimal", 0xFF38D0FF.toInt(), 0xFF060C18.toInt(), 0xFF122238.toInt(), 0.70f,
            setOf("perf", "thermal", "mem", "batt"), setOf("lock", "shot")),
        PanelPreset("performance", "Performance", 0xFF3DDC84.toInt(), 0xFF04120C.toInt(), 0xFF0C2418.toInt(), 0.70f,
            setOf("perf", "thermal", "mem", "net"), setOf("lock", "shot", "rec")),
    )

    fun apply(c: Context, p: PanelPreset) {
        Prefs.setPanelColors(c, p.accent, p.bg, p.card)
        Prefs.setOpacity(c, p.opacity)
        Prefs.setPanelCards(c, p.cards)
        Prefs.setShortcuts(c, p.shortcuts)
        Prefs.setPreset(c, p.id)
    }
}
