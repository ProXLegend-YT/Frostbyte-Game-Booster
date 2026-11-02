package com.frostbyte.gamebooster.data

import android.content.Context

/** One auto-click point. Two exist (index 0 and 1). */
data class ClickTarget(
    val index: Int,
    val enabled: Boolean,
    val x: Int,            // screen pixels, -1 = not placed yet
    val y: Int,
    val mode: Int,         // MODE_REPEAT or MODE_NETWORK
    val secRepeat: Int,    // seconds between taps in repeat mode
    val secNet: Int,       // seconds to wait after the internet comes back
    val holdMs: Int,
) {
    val seconds: Int get() = if (mode == MODE_NETWORK) secNet else secRepeat
    val placed: Boolean get() = x >= 0 && y >= 0

    companion object {
        const val MODE_REPEAT = 0
        const val MODE_NETWORK = 1
    }
}

object ClickerStore {
    const val COUNT = 2

    fun get(c: Context, i: Int): ClickTarget = ClickTarget(
        index = i,
        enabled = Prefs.getBool(c, "ct${i}_on", false),
        x = Prefs.getInt(c, "ct${i}_x", -1),
        y = Prefs.getInt(c, "ct${i}_y", -1),
        mode = Prefs.getInt(c, "ct${i}_mode", if (i == 0) ClickTarget.MODE_NETWORK else ClickTarget.MODE_REPEAT),
        secRepeat = Prefs.getInt(c, "ct${i}_srep", 60),
        secNet = Prefs.getInt(c, "ct${i}_snet", 5),
        holdMs = Prefs.getInt(c, "ct${i}_hold", 60),
    )

    fun all(c: Context): List<ClickTarget> = (0 until COUNT).map { get(c, it) }

    fun setEnabled(c: Context, i: Int, v: Boolean) = Prefs.putBool(c, "ct${i}_on", v)
    fun setMode(c: Context, i: Int, mode: Int) = Prefs.putInt(c, "ct${i}_mode", mode)
    fun setSeconds(c: Context, i: Int, mode: Int, v: Int) =
        Prefs.putInt(c, if (mode == ClickTarget.MODE_NETWORK) "ct${i}_snet" else "ct${i}_srep", v)
    fun setPos(c: Context, i: Int, x: Int, y: Int) {
        Prefs.putInt(c, "ct${i}_x", x)
        Prefs.putInt(c, "ct${i}_y", y)
    }
}
