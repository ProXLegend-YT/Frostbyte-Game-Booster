package com.frostbyte.gamebooster.system

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class RecentApp(val pkg: String, val label: String, val icon: Drawable?, val lastUsed: Long, val foregroundMs: Long)

object RecentApps {
    /** Apps you used in the last 24h (needs Usage Access). This is what Android lets a normal app observe. */
    @Suppress("DEPRECATION")
    fun load(c: Context, exclude: Set<String>): List<RecentApp> {
        if (!Perms.usage(c)) return emptyList()
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = try {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 24L * 3_600_000L, now)
        } catch (_: SecurityException) {
            return emptyList()
        } ?: return emptyList()

        val pm = c.packageManager
        val home = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()

        val merged = HashMap<String, Pair<Long, Long>>()   // pkg -> (lastUsed, foregroundMs)
        for (s in stats) {
            val prev = merged[s.packageName] ?: (0L to 0L)
            merged[s.packageName] = maxOf(prev.first, s.lastTimeUsed) to (prev.second + s.totalTimeInForeground)
        }

        return merged.mapNotNull { (pkg, v) ->
            if (pkg == c.packageName || pkg in exclude || pkg in home || v.second <= 0L) return@mapNotNull null
            try {
                val ai = pm.getApplicationInfo(pkg, 0)
                val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                if (system || pm.getLaunchIntentForPackage(pkg) == null) return@mapNotNull null
                RecentApp(pkg, pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai), v.first, v.second)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }.sortedByDescending { it.lastUsed }
    }
}
