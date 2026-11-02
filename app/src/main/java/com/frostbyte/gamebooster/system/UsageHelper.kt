package com.frostbyte.gamebooster.system

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

object UsageHelper {
    /** Last time each package came to the foreground in the past 14 days. Needs Usage Access; empty otherwise. */
    fun lastPlayed(c: Context, packages: Set<String>): Map<String, Long> {
        if (packages.isEmpty() || !Perms.usage(c)) return emptyMap()
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val out = HashMap<String, Long>()
        try {
            val events = usm.queryEvents(now - 14L * 86_400_000L, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && e.packageName in packages) {
                    val prev = out[e.packageName] ?: 0L
                    if (e.timeStamp > prev) out[e.packageName] = e.timeStamp
                }
            }
        } catch (_: SecurityException) {
            // permission revoked mid-query
        }
        return out
    }
}
