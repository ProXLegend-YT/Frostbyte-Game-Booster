package com.frostbyte.gamebooster.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One sampled finger position. [t] is milliseconds since the finger touched down. */
data class MacroPoint(val t: Int, val x: Float, val y: Float)

/**
 * One finger stroke: a tap, a hold, or a swipe/drag.
 * [startMs] is when it begins, counted from the first touch of the recording; [durationMs] is how long the finger stayed down.
 */
data class MacroStroke(val startMs: Long, val durationMs: Long, val pts: List<MacroPoint>)

/** A recording. [width]/[height] are the screen size it was made on, so playback can scale or refuse on a rotated screen. */
data class Macro(val width: Int, val height: Int, val strokes: List<MacroStroke>) {
    /** Time from the first touch to the moment the last finger lifts. */
    val totalMs: Long get() = strokes.lastOrNull()?.let { it.startMs + it.durationMs } ?: 0L
}

/** Saves the one recording to a small JSON file in the app's private storage (survives app restarts and updates). */
object MacroStore {
    const val MAX_STROKES = 3000

    private fun file(c: Context) = File(c.applicationContext.filesDir, "macro.json")

    fun load(c: Context): Macro? = try {
        val f = file(c)
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("s")
            val strokes = ArrayList<MacroStroke>(arr.length())
            for (i in 0 until arr.length()) {
                val so = arr.getJSONObject(i)
                val pa = so.getJSONArray("p")
                val pts = ArrayList<MacroPoint>(pa.length())
                for (j in 0 until pa.length()) {
                    val p = pa.getJSONArray(j)
                    pts += MacroPoint(p.getInt(0), p.getDouble(1).toFloat(), p.getDouble(2).toFloat())
                }
                if (pts.isNotEmpty()) strokes += MacroStroke(so.getLong("s"), so.getLong("d"), pts)
            }
            if (strokes.isEmpty()) null else Macro(o.getInt("w"), o.getInt("h"), strokes)
        }
    } catch (_: Exception) {
        null
    }

    fun save(c: Context, m: Macro): Boolean = try {
        val arr = JSONArray()
        for (s in m.strokes) {
            val pa = JSONArray()
            for (p in s.pts) {
                pa.put(JSONArray().put(p.t).put(Math.round(p.x * 10f) / 10.0).put(Math.round(p.y * 10f) / 10.0))
            }
            arr.put(JSONObject().put("s", s.startMs).put("d", s.durationMs).put("p", pa))
        }
        val o = JSONObject().put("w", m.width).put("h", m.height).put("s", arr)
        val tmp = File(file(c).path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file(c)) || run { file(c).writeText(o.toString()); true }
    } catch (_: Exception) {
        false
    }

    fun clear(c: Context) {
        try { file(c).delete() } catch (_: Exception) { }
    }
}
