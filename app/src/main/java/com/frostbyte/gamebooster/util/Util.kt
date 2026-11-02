package com.frostbyte.gamebooster.util

import android.content.Context

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

object Fmt {
    fun duration(ms: Long): String {
        val totalMin = ms / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    fun ago(nowMs: Long, thenMs: Long): String {
        val d = nowMs - thenMs
        return when {
            d < 60_000 -> "just now"
            d < 3_600_000 -> "${d / 60_000}m ago"
            d < 86_400_000 -> "${d / 3_600_000}h ago"
            else -> "${d / 86_400_000}d ago"
        }
    }
}

object Estimates {
    /**
     * Remaining play time based on how fast the battery ACTUALLY drained this session.
     * Returns null (shown as "Measuring…") until there's enough real data. Never guesses.
     */
    fun remainingMs(startPct: Int?, nowPct: Int?, elapsedMs: Long, charging: Boolean): Long? {
        if (charging || startPct == null || nowPct == null) return null
        val drop = startPct - nowPct
        if (drop < 2 || elapsedMs < 5 * 60_000L) return null
        val perMs = drop.toDouble() / elapsedMs
        return (nowPct / perMs).toLong()
    }
}
