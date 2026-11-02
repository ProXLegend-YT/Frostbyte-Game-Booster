package com.frostbyte.gamebooster.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** A game FrostByte knows how to look for. */
data class GameDef(val id: String, val name: String, val packages: List<String>)

data class InstalledGame(
    val def: GameDef,
    val packageName: String,
    val label: String,
    val icon: Drawable?,
) {
    val isCustom: Boolean get() = def.id.startsWith("custom:")
}

object Games {
    val known = listOf(
        GameDef("roblox", "Roblox", listOf("com.roblox.client")),
        GameDef("minecraft", "Minecraft Bedrock", listOf("com.mojang.minecraftpe")),
        GameDef("pojav", "PojavLauncher", listOf("net.kdt.pojavlaunch", "net.kdt.pojavlaunch.debug")),
    )

    @Suppress("DEPRECATION")
    fun detectInstalled(context: Context): List<InstalledGame> {
        val pm = context.packageManager
        val found = mutableListOf<InstalledGame>()
        for (def in known) {
            for (pkg in def.packages) {
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    found += InstalledGame(def, pkg, def.name, pm.getApplicationIcon(info))
                    break
                } catch (_: PackageManager.NameNotFoundException) {
                    // not installed, try next package id
                }
            }
        }
        // games the user added by hand ("any other installed game")
        for (pkg in Prefs.customGames(context)) {
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(info).toString()
                found += InstalledGame(GameDef("custom:$pkg", label, listOf(pkg)), pkg, label, pm.getApplicationIcon(info))
            } catch (_: PackageManager.NameNotFoundException) {
                // uninstalled since it was added
            }
        }
        return found
    }

    /** Launchable apps, for the "Add game" picker. */
    @Suppress("DEPRECATION")
    fun launcherApps(context: Context): List<InstalledGame> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val skip = known.flatMap { it.packages }.toSet() + context.packageName + Prefs.customGames(context)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it }
            .filter { it.first !in skip }
            .distinctBy { it.first }
            .map { (pkg, ri) ->
                val label = ri.loadLabel(pm).toString()
                InstalledGame(GameDef("custom:$pkg", label, listOf(pkg)), pkg, label, ri.loadIcon(pm))
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Returns true if the game was launched. */
    fun launch(context: Context, game: InstalledGame): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(game.packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}
