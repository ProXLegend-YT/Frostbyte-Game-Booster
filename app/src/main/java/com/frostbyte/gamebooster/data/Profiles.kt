package com.frostbyte.gamebooster.data

data class Effective(val blockNotifications: Boolean, val blockCalls: Boolean, val keepAwake: Boolean)

/** What a profile REALLY does: which interruptions get blocked, whether the screen stays awake, how often we check stats. */
enum class GameProfile(val label: String, val blurb: String, val monitorMs: Long) {
    PERFORMANCE("Performance", "Do Not Disturb on, screen stays awake, light monitoring", 8_000),
    BALANCED("Balanced", "Do Not Disturb on, screen follows your normal timeout", 5_000),
    BATTERY("Battery", "Do Not Disturb on, lightest monitoring, battery tips", 10_000),
    CUSTOM("Custom", "Uses your own Block During Game switches", 5_000);
}

object Profiles {
    fun effective(c: android.content.Context, p: GameProfile): Effective = when (p) {
        GameProfile.PERFORMANCE -> Effective(blockNotifications = true, blockCalls = false, keepAwake = true)
        GameProfile.BALANCED -> Effective(true, false, false)
        GameProfile.BATTERY -> Effective(true, false, false)
        GameProfile.CUSTOM -> Prefs.custom(c)
    }
}

/** Honest, manual recommendations. FrostByte never edits game files. */
object GameTips {
    fun tip(gameId: String, p: GameProfile): String = when {
        gameId == "roblox" -> when (p) {
            GameProfile.PERFORMANCE -> "Roblox: Settings > Graphics Mode > Manual, quality 1-3. Close other apps for steadier frames."
            GameProfile.BALANCED -> "Roblox: Automatic graphics is a good balance for most servers."
            GameProfile.BATTERY -> "Roblox: set Graphics Quality to 1-2 and lower screen brightness to save battery."
            GameProfile.CUSTOM -> "Custom: you choose what FrostByte blocks while Roblox runs."
        }
        gameId == "minecraft" -> when (p) {
            GameProfile.PERFORMANCE -> "Minecraft: Settings > Video: render distance 6-8, Fancy Graphics off, Max Frame Rate 30-45 to keep heat down."
            GameProfile.BALANCED -> "Minecraft: render distance 8-10 is fine for short sessions."
            GameProfile.BATTERY -> "Minecraft: render distance 4-6 and a 30 FPS cap stretch battery life."
            GameProfile.CUSTOM -> "Custom: you choose what FrostByte blocks while Minecraft runs."
        }
        gameId == "pojav" -> when (p) {
            GameProfile.PERFORMANCE -> "PojavLauncher: on a 4 GB phone allocate ~1000-1500 MB RAM (more can get the game killed), render distance 4-6, resolution scale 50-75%. Shaders are very heavy here."
            GameProfile.BALANCED -> "PojavLauncher: ~1500 MB RAM, render distance 6-8, resolution scale 75%. Avoid shaders."
            GameProfile.BATTERY -> "PojavLauncher: render distance 4, resolution scale 50%, cap FPS to 30."
            GameProfile.CUSTOM -> "Custom: you choose what FrostByte blocks while PojavLauncher runs."
        }
        else -> when (p) {
            GameProfile.PERFORMANCE -> "Lower in-game graphics and close other apps for the smoothest play."
            GameProfile.BALANCED -> "A balanced setup: default graphics, notifications silenced."
            GameProfile.BATTERY -> "Lower brightness and graphics to play longer."
            GameProfile.CUSTOM -> "Custom: you choose what FrostByte blocks."
        }
    }
}
