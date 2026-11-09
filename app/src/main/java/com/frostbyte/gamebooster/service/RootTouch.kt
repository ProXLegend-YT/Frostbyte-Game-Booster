package com.frostbyte.gamebooster.service

import com.frostbyte.gamebooster.data.MacroPoint
import com.frostbyte.gamebooster.data.MacroStroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.math.hypot
import kotlin.math.max

/**
 * Live touch recording, the way Tiny Task works on a PC: you use the phone normally and every touch is logged.
 *
 * Android does not let an ordinary app watch touches that go to another app (an overlay either blocks them or never
 * sees them). The system's own `getevent` tool can, but only with root. This reads that tool's output, so it needs a
 * rooted phone (Magisk or similar). Without root, the recorder falls back to its capture mode.
 */
object RootTouch {

    /** The touchscreen's input device and the size of its raw coordinate grid. */
    class Panel(val path: String, val maxX: Int, val maxY: Int, val shizuku: Boolean)

    /** Either a [panel] ready to record from, or an [error] saying why not. */
    class Prepared(val panel: Panel?, val error: String?)

    private const val MAX_POINTS_PER_STROKE = 600

    /**
     * Gets elevated access (Shizuku if you allowed it, otherwise root; a prompt may appear the first time) and finds the
     * touchscreen. Blocking: call off the main thread.
     */
    fun prepare(): Prepared {
        val viaShizuku = Priv.shizukuGranted()
        if (!viaShizuku) {
            try {
                val idp = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                if (!idp.waitFor(25, TimeUnit.SECONDS)) {
                    idp.destroy()
                    return Prepared(null, "the root request timed out")
                }
                val idOut = idp.inputStream.bufferedReader().readText()
                if (!idOut.contains("uid=0")) return Prepared(null, "root access was denied")
            } catch (_: Exception) {
                return Prepared(null, "no root, and Shizuku isn't allowed yet")
            }
        }

        val text = try {
            val lp = Priv.spawn("getevent -lp", viaShizuku)
            val out = lp.inputStream.bufferedReader().readText()
            try { lp.waitFor(5, TimeUnit.SECONDS) } catch (_: Exception) { }
            out
        } catch (_: Exception) {
            return Prepared(null, "couldn't run getevent")
        }

        var path: String? = null
        var maxX = -1
        var maxY = -1
        var hasId = false
        var best: Panel? = null
        fun flush() {
            val p = path
            if (best == null && p != null && maxX > 0 && maxY > 0 && hasId) best = Panel(p, maxX, maxY, viaShizuku)
        }
        fun maxVal(line: String): Int = Regex("max\\s+(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        for (line in text.lines()) {
            val t = line.trim()
            when {
                t.startsWith("add device") -> {
                    flush()
                    path = t.substringAfter(": ", "").trim().ifEmpty { null }
                    maxX = -1
                    maxY = -1
                    hasId = false
                }
                t.contains("ABS_MT_POSITION_X") -> maxX = maxVal(t)
                t.contains("ABS_MT_POSITION_Y") -> maxY = maxVal(t)
                t.contains("ABS_MT_TRACKING_ID") -> hasId = true
            }
        }
        flush()
        val found = best ?: return Prepared(null, "no multi-touch screen found in getevent")
        return Prepared(found, null)
    }

    /**
     * Reads `getevent` and turns the raw log into [MacroStroke]s (one finger: taps, holds, swipes), already converted to
     * screen pixels. Strokes are delivered on a background thread with absolute start times in milliseconds; the caller
     * subtracts the first one.
     */
    class LiveReader(
        private val panel: Panel,
        private val w: Int,
        private val h: Int,
        private val rotation: Int,
        private val onStroke: (MacroStroke) -> Unit,
        private val onError: (String) -> Unit,
    ) {
        @Volatile
        private var stopped = false
        private var proc: Process? = null

        private class Slot {
            var x = 0
            var y = 0
            var down = false
            var up = false
            var moved = false
        }

        fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) { runLoop() }

        fun stop() {
            stopped = true
            try { proc?.destroy() } catch (_: Exception) { }
            try { Priv.spawn("pkill -f 'getevent -lt'", panel.shizuku) } catch (_: Throwable) { }
        }

        private fun hex(v: String): Int = try { java.lang.Long.parseLong(v, 16).toInt() } catch (_: Exception) { 0 }

        /** Raw panel coordinates (always in the phone's natural portrait orientation) to pixels on the current screen. */
        private fun map(rx: Int, ry: Int): Pair<Float, Float> {
            val nx = (rx.toFloat() / panel.maxX).coerceIn(0f, 1f)
            val ny = (ry.toFloat() / panel.maxY).coerceIn(0f, 1f)
            val fw = (w - 1).toFloat()
            val fh = (h - 1).toFloat()
            return when (rotation) {
                1 -> Pair(ny * fw, (1f - nx) * fh)
                2 -> Pair((1f - nx) * fw, (1f - ny) * fh)
                3 -> Pair((1f - ny) * fw, nx * fh)
                else -> Pair(nx * fw, ny * fh)
            }
        }

        private fun runLoop() {
            val p = try {
                Priv.spawn("exec getevent -lt ${panel.path}", panel.shizuku)
            } catch (_: Throwable) {
                onError("couldn't start getevent")
                return
            }
            proc = p
            val re = Regex("^\\[\\s*(\\d+\\.\\d+)\\]\\s+(?:\\S+:\\s+)?(EV_\\w+)\\s+(\\w+)\\s+(\\S+)")
            val slots = HashMap<Int, Slot>()
            var slot = 0
            var primary = -1          // the slot (finger) being recorded; other fingers are ignored
            var downTs = 0.0
            var pts: ArrayList<MacroPoint>? = null
            var lastX = 0f
            var lastY = 0f
            try {
                val rd = p.inputStream.bufferedReader()
                while (!stopped) {
                    val line = rd.readLine() ?: break
                    val m = re.find(line) ?: continue
                    val ts = m.groupValues[1].toDouble()
                    val type = m.groupValues[2]
                    val code = m.groupValues[3]
                    val v = m.groupValues[4]
                    if (type == "EV_ABS") {
                        when (code) {
                            "ABS_MT_SLOT" -> slot = hex(v)
                            "ABS_MT_TRACKING_ID" -> {
                                val s = slots.getOrPut(slot) { Slot() }
                                if (hex(v) == -1) s.up = true else s.down = true
                            }
                            "ABS_MT_POSITION_X" -> slots.getOrPut(slot) { Slot() }.apply { x = hex(v); moved = true }
                            "ABS_MT_POSITION_Y" -> slots.getOrPut(slot) { Slot() }.apply { y = hex(v); moved = true }
                        }
                    } else if (type == "EV_SYN" && code == "SYN_REPORT") {
                        if (primary == -1) {
                            val e = slots.entries.firstOrNull { it.value.down }
                            if (e != null) {
                                primary = e.key
                                downTs = ts
                                val (px, py) = map(e.value.x, e.value.y)
                                lastX = px
                                lastY = py
                                pts = arrayListOf(MacroPoint(0, px, py))
                            }
                        } else {
                            val s = slots[primary]
                            val list = pts
                            if (s != null && list != null) {
                                val (px, py) = map(s.x, s.y)
                                val t = ((ts - downTs) * 1000.0).toInt()
                                if (s.up) {
                                    if (hypot(px - lastX, py - lastY) >= 1f) list += MacroPoint(t, px, py)
                                    onStroke(MacroStroke((downTs * 1000.0).toLong(), max(1L, t.toLong()), ArrayList(list)))
                                    primary = -1
                                    pts = null
                                } else if (s.moved && list.size < MAX_POINTS_PER_STROKE && hypot(px - lastX, py - lastY) >= 3f) {
                                    list += MacroPoint(t, px, py)
                                    lastX = px
                                    lastY = py
                                }
                            }
                        }
                        for (s in slots.values) {
                            s.down = false
                            s.up = false
                            s.moved = false
                        }
                    }
                }
            } catch (e: Exception) {
                if (!stopped) onError(e.message ?: "read error")
                return
            } finally {
                try { p.destroy() } catch (_: Exception) { }
            }
            if (!stopped) onError("getevent stopped unexpectedly")
        }
    }
}
