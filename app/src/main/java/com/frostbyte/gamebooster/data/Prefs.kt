package com.frostbyte.gamebooster.data

import android.content.Context

/** All settings live in one small local SharedPreferences file. Nothing leaves the phone. */
object Prefs {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("frostbyte", Context.MODE_PRIVATE)

    fun profile(c: Context, gameId: String): GameProfile =
        GameProfile.entries.firstOrNull { it.name == sp(c).getString("profile_$gameId", null) } ?: (if (gameId == "roblox") GameProfile.PERFORMANCE else GameProfile.BALANCED)

    fun setProfile(c: Context, gameId: String, p: GameProfile) {
        sp(c).edit().putString("profile_$gameId", p.name).apply()
    }

    fun custom(c: Context): Effective = sp(c).let {
        Effective(it.getBoolean("c_notif", true), it.getBoolean("c_calls", false), it.getBoolean("c_awake", false))
    }

    fun setCustom(c: Context, e: Effective) {
        sp(c).edit().putBoolean("c_notif", e.blockNotifications).putBoolean("c_calls", e.blockCalls)
            .putBoolean("c_awake", e.keepAwake).apply()
    }

    fun opacity(c: Context): Float = sp(c).getFloat("opacity", 0.78f)
    fun setOpacity(c: Context, v: Float) { sp(c).edit().putFloat("opacity", v).apply() }

    fun reducedMotion(c: Context): Boolean = sp(c).getBoolean("reduced_motion", false)
    fun setReducedMotion(c: Context, v: Boolean) { sp(c).edit().putBoolean("reduced_motion", v).apply() }

    fun pingEnabled(c: Context): Boolean = sp(c).getBoolean("ping", true)
    fun setPingEnabled(c: Context, v: Boolean) { sp(c).edit().putBoolean("ping", v).apply() }

    fun customGames(c: Context): Set<String> = HashSet(sp(c).getStringSet("custom_games", emptySet()) ?: emptySet())
    fun addCustomGame(c: Context, pkg: String) { sp(c).edit().putStringSet("custom_games", customGames(c) + pkg).apply() }
    fun removeCustomGame(c: Context, pkg: String) { sp(c).edit().putStringSet("custom_games", customGames(c) - pkg).apply() }

    // Do Not Disturb bookkeeping so we can restore the user's own setting afterwards.
    fun dndOwned(c: Context): Boolean = sp(c).getBoolean("dnd_owned", false)
    fun savedDnd(c: Context): Int = sp(c).getInt("dnd_saved", 1)
    fun setDnd(c: Context, owned: Boolean, saved: Int) { sp(c).edit().putBoolean("dnd_owned", owned).putInt("dnd_saved", saved).apply() }

    fun sessionsJson(c: Context): String = sp(c).getString("sessions", "[]") ?: "[]"
    fun setSessionsJson(c: Context, s: String) { sp(c).edit().putString("sessions", s).apply() }

    // ---- panel customisation ----
    val ALL_CARDS: Set<String> = setOf("perf", "thermal", "mem", "batt", "net", "block")
    val ALL_SHORTCUTS: Set<String> = setOf("boost", "lock", "shot", "rec", "afk", "vol", "net", "settings")

    fun panelCards(c: Context): Set<String> = HashSet(sp(c).getStringSet("panel_cards", null) ?: ALL_CARDS)
    fun setPanelCards(c: Context, s: Set<String>) { sp(c).edit().putStringSet("panel_cards", HashSet(s)).apply() }
    fun shortcuts(c: Context): Set<String> = HashSet(sp(c).getStringSet("panel_shortcuts", null) ?: ALL_SHORTCUTS)
    fun setShortcuts(c: Context, s: Set<String>) { sp(c).edit().putStringSet("panel_shortcuts", HashSet(s)).apply() }

    fun panelAccent(c: Context): Int = sp(c).getInt("panel_accent", 0xFF38D0FF.toInt())
    fun panelBg(c: Context): Int = sp(c).getInt("panel_bg", 0xFF060C18.toInt())
    fun panelCard(c: Context): Int = sp(c).getInt("panel_card", 0xFF122238.toInt())
    fun setPanelColors(c: Context, accent: Int, bg: Int, card: Int) {
        sp(c).edit().putInt("panel_accent", accent).putInt("panel_bg", bg).putInt("panel_card", card).apply()
    }

    fun preset(c: Context): String = sp(c).getString("preset", "frost") ?: "frost"
    fun setPreset(c: Context, id: String) { sp(c).edit().putString("preset", id).apply() }

    fun amoled(c: Context): Boolean = sp(c).getBoolean("amoled", false)
    fun setAmoled(c: Context, v: Boolean) { sp(c).edit().putBoolean("amoled", v).apply() }

    // ---- AFK mode ----
    fun afkTouchLock(c: Context): Boolean = sp(c).getBoolean("afk_lock", false)
    fun setAfkTouchLock(c: Context, v: Boolean) { sp(c).edit().putBoolean("afk_lock", v).apply() }
    fun afkLowPower(c: Context): Boolean = sp(c).getBoolean("afk_lowpower", true)
    fun setAfkLowPower(c: Context, v: Boolean) { sp(c).edit().putBoolean("afk_lowpower", v).apply() }
    fun afkStopBattery(c: Context): Int = sp(c).getInt("afk_stop_batt", 15)
    fun setAfkStopBattery(c: Context, v: Int) { sp(c).edit().putInt("afk_stop_batt", v).apply() }
    fun afkStopHot(c: Context): Boolean = sp(c).getBoolean("afk_stop_hot", true)
    fun setAfkStopHot(c: Context, v: Boolean) { sp(c).edit().putBoolean("afk_stop_hot", v).apply() }

    // ---- gaming extras (all opt-in, always restored) ----
    fun sweepEnabled(c: Context): Boolean = sp(c).getBoolean("sweep", true)
    fun setSweepEnabled(c: Context, v: Boolean) { sp(c).edit().putBoolean("sweep", v).apply() }
    fun pauseSync(c: Context): Boolean = sp(c).getBoolean("pause_sync", false)
    fun setPauseSync(c: Context, v: Boolean) { sp(c).edit().putBoolean("pause_sync", v).apply() }
    fun lowerBrightness(c: Context): Boolean = sp(c).getBoolean("low_bright", false)
    fun setLowerBrightness(c: Context, v: Boolean) { sp(c).edit().putBoolean("low_bright", v).apply() }
    fun brightnessPct(c: Context): Int = sp(c).getInt("bright_pct", 50)
    fun setBrightnessPct(c: Context, v: Int) { sp(c).edit().putInt("bright_pct", v).apply() }

    fun brightOwned(c: Context): Boolean = sp(c).getBoolean("bright_owned", false)
    fun savedBrightMode(c: Context): Int = sp(c).getInt("bright_mode", 1)
    fun savedBrightValue(c: Context): Int = sp(c).getInt("bright_value", 128)
    fun setBright(c: Context, owned: Boolean, mode: Int, value: Int) {
        sp(c).edit().putBoolean("bright_owned", owned).putInt("bright_mode", mode).putInt("bright_value", value).apply()
    }
    fun syncOwned(c: Context): Boolean = sp(c).getBoolean("sync_owned", false)
    fun savedSync(c: Context): Boolean = sp(c).getBoolean("sync_saved", true)
    fun setSync(c: Context, owned: Boolean, saved: Boolean) {
        sp(c).edit().putBoolean("sync_owned", owned).putBoolean("sync_saved", saved).apply()
    }
}
