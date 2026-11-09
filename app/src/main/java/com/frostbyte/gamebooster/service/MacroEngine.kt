package com.frostbyte.gamebooster.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.frostbyte.gamebooster.data.Macro
import com.frostbyte.gamebooster.data.MacroPoint
import com.frostbyte.gamebooster.data.MacroStore
import com.frostbyte.gamebooster.data.MacroStroke
import com.frostbyte.gamebooster.data.Prefs
import com.frostbyte.gamebooster.util.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot
import kotlin.math.max

/**
 * Macro recorder: "Tiny Task for phones".
 *
 *  - RECORD: a see-through full-screen layer captures one finger (taps, holds, swipes) with exact timing.
 *    Each finished touch is also sent through to the game right after you lift, so menus advance as you record.
 *  - PLAY: replays every touch with the recorded timing, using the same tap mechanism as the Auto Clicker.
 *  - LOOP: plays, waits (default 4 s), plays again, until you stop it.
 *  - Playback stops by itself if the screen turns off. The ghost-touch shield from the Auto Clicker works here too.
 *
 * It runs inside [ClickerService] (Android allows only one accessibility service per app) and uses the same
 * overlay, shield, keep-alive notification and restore-after-kill machinery.
 */
class MacroEngine(private val svc: ClickerService) {

    companion object {
        /** The shield opens its touch hole this long before a stroke is due so the tap lands exactly on time. */
        private const val HOLE_LEAD_MS = 60L

        /** Finger wobble smaller than this counts as a hold, not a drag. */
        private const val MOVE_SLOP_PX = 14f

        private const val MAX_POINTS_PER_STROKE = 600
    }

    private enum class Mode { IDLE, RECORDING, PLAYING }

    @Volatile
    private var mode = Mode.IDLE
    private var looping = false
    private var runId = 0
    private var stopReason: String? = null

    // ---- floating bar
    private var bar: LinearLayout? = null
    private var barLp: WindowManager.LayoutParams? = null
    private var statusView: TextView? = null
    private var btnRec: TextView? = null
    private var btnPlay: TextView? = null
    private var btnLoop: TextView? = null
    private var btnShield: TextView? = null
    private var savedCount = 0
    private var savedMs = 0L

    // ---- recording
    private var recView: TrailView? = null
    private var recLp: WindowManager.LayoutParams? = null
    private val strokes = ArrayList<MacroStroke>()
    private var firstDown = -1L
    private var recStartedAt = 0L
    private var curPts: ArrayList<MacroPoint>? = null
    private var curDown = 0L
    private var curStartOffset = 0L
    private var curId = -1
    private var recTicker: Job? = null
    private var fwdQueue: Channel<MacroStroke>? = null
    private var fwdJob: Job? = null

    // ---- playback
    private var playJob: Job? = null
    private var hole = false
    private var screenRx: BroadcastReceiver? = null

    val barVisible: Boolean get() = bar != null
    val busy: Boolean get() = mode != Mode.IDLE
    val isLooping: Boolean get() = mode == Mode.PLAYING && looping

    private fun dp(v: Int) = svc.dp(v)

    private fun canTouch(): Boolean = (svc.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    // ------------------------------------------------------------------ bar (show / hide / restore)

    fun showBar() {
        Prefs.setMacroSession(svc, true)
        refreshSaved()
        if (bar == null) buildBar()
        svc.startWatchdog()
        svc.startKeepAlive()
        svc.bumpShield()
    }

    /** The user pressed the cross: stop everything and forget the session so it doesn't come back by itself. */
    fun hideBar() {
        stopAll(null)
        removeBar()
        Prefs.setMacroSession(svc, false)
        Prefs.setMacroLooping(svc, false)
        svc.maybeStopKeepAlive()
    }

    /** Called after Android restarted the service: bring the bar back and resume the loop if it was running. */
    fun restore() {
        if (!Prefs.macroSession(svc)) return
        showBar()
        if (Prefs.macroLooping(svc) && canTouch()) startPlay(loop = true, quiet = true)
        else Prefs.setMacroLooping(svc, false)
    }

    /** Removes everything and stops all work. Saved state is only touched when [forget]. */
    fun teardown(forget: Boolean) {
        runId++   // makes a cancelled playback coroutine skip its cleanup, so the saved loop state survives a restart
        recTicker?.cancel()
        fwdJob?.cancel()
        fwdQueue?.close()
        playJob?.cancel()
        removeRecView()
        removeBar()
        watchScreenOff(false)
        hole = false
        mode = Mode.IDLE
        looping = false
        if (forget) {
            Prefs.setMacroSession(svc, false)
            Prefs.setMacroLooping(svc, false)
        }
    }

    /** Run every few seconds by the service watchdog: put back whatever the system tore down. */
    fun heal() {
        if (Prefs.macroSession(svc) || bar != null) {
            val b = bar
            if (b != null && !b.isAttachedToWindow && mode != Mode.RECORDING) {
                try { svc.wm.removeView(b) } catch (_: Exception) { }
                bar = null
                buildBar(quiet = true)
                svc.bumpShield()
            }
        }
        if (mode == Mode.PLAYING && playJob?.isActive != true) finishPlayback(null)
        if (mode == Mode.RECORDING && recView?.isAttachedToWindow != true && SystemClock.elapsedRealtime() - recStartedAt > 2000) {
            stopRecording(save = true)
        }
        refreshButtons()
    }

    private fun refreshSaved() {
        val m = MacroStore.load(svc)
        savedCount = m?.strokes?.size ?: 0
        savedMs = m?.totalMs ?: 0L
        if (mode == Mode.IDLE) setStatus(idleStatus())
    }

    private fun idleStatus(): String =
        if (savedCount == 0) "Macro\nno recording" else "Macro\n$savedCount touches\n${fmt(savedMs)}"

    // ------------------------------------------------------------------ bar UI

    private fun buildBar(quiet: Boolean = false) {
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            background = svc.pill()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun btn(label: String, onClick: () -> Unit): TextView = TextView(svc).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setOnClickListener { onClick() }
        }
        val st = TextView(svc).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 3
            text = idleStatus()
        }
        statusView = st
        box.addView(st, LinearLayout.LayoutParams(dp(58), ViewGroup.LayoutParams.WRAP_CONTENT))

        val rec = btn("\u25CF") { onRecClick() }
        val play = btn("\u25B6") { onPlayClick() }
        val loop = btn("\uD83D\uDD01") { onLoopClick() }
        val shield = btn("\uD83D\uDEE1") { svc.toggleShield(); refreshButtons() }
        btnRec = rec; btnPlay = play; btnLoop = loop; btnShield = shield
        for (b in listOf(rec, play, loop, shield)) box.addView(b, LinearLayout.LayoutParams(dp(58), dp(44)))
        box.addView(btn("\u2715") { hideBar() }, LinearLayout.LayoutParams(dp(58), dp(44)))

        val (sw, sh) = svc.screenSize()
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, svc.overlayFlags(), PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sw - dp(72)
            y = sh / 6
        }
        val handle = btn("\u2725") { }
        var offX = 0f
        var offY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { offX = e.rawX - lp.x; offY = e.rawY - lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (e.rawX - offX).toInt()
                    lp.y = (e.rawY - offY).toInt()
                    try { svc.wm.updateViewLayout(box, lp) } catch (_: Exception) { }
                }
            }
            true
        }
        box.addView(handle, LinearLayout.LayoutParams(dp(58), dp(44)))
        try {
            svc.wm.addView(box, lp)
            bar = box
            barLp = lp
            refreshButtons()
        } catch (_: Exception) {
            if (!quiet) svc.toast("Couldn't show the macro bar")
        }
    }

    private fun removeBar() {
        bar?.let { try { svc.wm.removeView(it) } catch (_: Exception) { } }
        bar = null
        barLp = null
        statusView = null
        btnRec = null; btnPlay = null; btnLoop = null; btnShield = null
    }

    private fun bringBarToFront() {
        val b = bar ?: return
        val lp = barLp ?: return
        try {
            svc.wm.removeView(b)
            svc.wm.addView(b, lp)
        } catch (_: Exception) { }
    }

    private fun setStatus(t: String) { statusView?.text = t }

    private fun refreshButtons() {
        val rec = mode == Mode.RECORDING
        val playOnce = mode == Mode.PLAYING && !looping
        val loop = mode == Mode.PLAYING && looping
        btnRec?.text = if (rec) "\u25A0" else "\u25CF"
        btnRec?.setTextColor(Color.parseColor("#FF5C5C"))
        btnPlay?.text = if (playOnce) "\u25A0" else "\u25B6"
        btnPlay?.setTextColor(if (playOnce) Color.parseColor("#3DDC84") else Color.WHITE)
        btnLoop?.text = if (loop) "\u25A0" else "\uD83D\uDD01"
        btnLoop?.setTextColor(if (loop) Color.parseColor("#3DDC84") else Color.WHITE)
        btnShield?.alpha = if (svc.hasShield()) 1f else 0.55f
    }

    /** Keeps the display on while recording or playing, so AFK loops don't hit the screen timeout. */
    private fun setKeepScreen(on: Boolean) {
        val b = bar ?: return
        val lp = barLp ?: return
        lp.flags = if (on) lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        try { svc.wm.updateViewLayout(b, lp) } catch (_: Exception) { }
    }

    // ------------------------------------------------------------------ bar actions

    private fun onRecClick() {
        when (mode) {
            Mode.RECORDING -> stopRecording(save = true)
            Mode.PLAYING -> { stopAll("Stopped"); startRecording() }
            Mode.IDLE -> startRecording()
        }
    }

    private fun onPlayClick() {
        when (mode) {
            Mode.RECORDING -> svc.toast("Stop recording first (tap \u25A0)")
            Mode.PLAYING -> stopAll("Stopped")
            Mode.IDLE -> startPlay(loop = false)
        }
    }

    private fun onLoopClick() {
        when (mode) {
            Mode.RECORDING -> svc.toast("Stop recording first (tap \u25A0)")
            Mode.PLAYING -> stopAll("Stopped")
            Mode.IDLE -> startPlay(loop = true)
        }
    }

    /** Stops whatever is happening. A recording in progress is kept (saved) as far as it got. */
    fun stopAll(msg: String?) {
        when (mode) {
            Mode.RECORDING -> stopRecording(save = true)
            Mode.PLAYING -> {
                runId++
                playJob?.cancel()
                finishPlayback(msg)
                return
            }
            Mode.IDLE -> {}
        }
        if (msg != null) svc.toast(msg)
    }

    // ------------------------------------------------------------------ recording

    private fun startRecording() {
        if (bar == null) return
        if (!canTouch()) { svc.toast("Turn the screen on first"); return }
        svc.stopClickerForMacro()
        if (svc.hasShield()) {
            svc.removeShieldForMacro()
            svc.toast("Touch shield turned off while recording")
        }
        strokes.clear()
        firstDown = -1L
        curPts = null
        curId = -1

        val tv = TrailView(svc)
        tv.setBackgroundColor(Color.argb(34, 255, 60, 60))   // red tint = "recording, your touches are being captured"
        tv.setOnTouchListener { _, e -> onRecTouch(e); true }
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            svc.overlayFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON), PixelFormat.TRANSLUCENT,
        )
        try {
            svc.wm.addView(tv, lp)
        } catch (_: Exception) {
            svc.toast("Couldn't start recording")
            return
        }
        recView = tv
        recLp = lp
        mode = Mode.RECORDING
        recStartedAt = SystemClock.elapsedRealtime()
        bringBarToFront()   // the bar must stay above the recording layer so \u25A0 can be tapped
        setKeepScreen(true)
        watchScreenOff(true)
        if (Prefs.macroForward(svc)) startForwarder()
        recTicker?.cancel()
        recTicker = svc.scope.launch {
            while (isActive) {
                setStatus("\u25CF REC ${fmt(SystemClock.elapsedRealtime() - recStartedAt)}\n${strokes.size} touches")
                delay(400)
            }
        }
        refreshButtons()
        svc.toast("Recording: do your taps, holds and swipes now. Tap \u25A0 on the bar when you're done.")
    }

    private fun stopRecording(save: Boolean) {
        if (mode != Mode.RECORDING) return
        mode = Mode.IDLE
        recTicker?.cancel()
        fwdJob?.cancel()
        fwdQueue?.close()
        fwdQueue = null
        curPts = null
        removeRecView()
        watchScreenOff(false)
        setKeepScreen(false)
        val list = ArrayList(strokes)
        if (save) {
            if (list.isEmpty()) {
                svc.toast("Nothing was recorded")
            } else {
                val (w, h) = svc.screenSize()
                val m = Macro(w, h, list)
                if (MacroStore.save(svc, m)) {
                    svc.toast("Saved ${list.size} touches (${fmt(m.totalMs)}). Tap \u25B6 to test it.")
                } else {
                    svc.toast("Couldn't save the recording")
                }
            }
        }
        refreshSaved()
        refreshButtons()
    }

    private fun removeRecView() {
        recView?.let { try { svc.wm.removeView(it) } catch (_: Exception) { } }
        recView = null
        recLp = null
    }

    private fun onRecTouch(e: MotionEvent) {
        val tv = recView ?: return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (strokes.size >= MacroStore.MAX_STROKES) {
                    svc.toast("Recording is full (${MacroStore.MAX_STROKES} touches): stopping")
                    svc.main.post { stopRecording(save = true) }
                    return
                }
                curId = e.getPointerId(0)
                curDown = e.downTime
                if (firstDown < 0) firstDown = e.downTime
                curStartOffset = e.downTime - firstDown
                curPts = arrayListOf(MacroPoint(0, e.rawX, e.rawY))
                tv.begin(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val pts = curPts ?: return
                val idx = e.findPointerIndex(curId)
                if (idx < 0) return
                val ox = e.rawX - e.x   // window offset, so raw screen coordinates can be rebuilt for any pointer
                val oy = e.rawY - e.y
                val x = e.getX(idx) + ox
                val y = e.getY(idx) + oy
                val last = pts.last()
                if (pts.size < MAX_POINTS_PER_STROKE && hypot(x - last.x, y - last.y) >= 3f) {
                    pts += MacroPoint((e.eventTime - curDown).toInt(), x, y)
                    tv.moveTo(e.getX(idx), e.getY(idx))
                }
            }
            MotionEvent.ACTION_UP -> {
                val pts = curPts ?: return
                val idx = e.findPointerIndex(curId)
                val ox = e.rawX - e.x
                val oy = e.rawY - e.y
                val x = if (idx >= 0) e.getX(idx) + ox else pts.last().x
                val y = if (idx >= 0) e.getY(idx) + oy else pts.last().y
                val dur = max(1L, e.eventTime - curDown)
                val last = pts.last()
                if (hypot(x - last.x, y - last.y) >= 1f) pts += MacroPoint(dur.toInt(), x, y)
                val s = MacroStroke(curStartOffset, dur, ArrayList(pts))
                strokes += s
                curPts = null
                tv.end()
                fwdQueue?.trySend(s)   // let the game see this touch too
            }
            MotionEvent.ACTION_CANCEL -> {
                // The system took the touch away (for example while we were forwarding the previous one): don't keep half a stroke.
                curPts = null
                tv.end()
            }
        }
    }

    /** Sends each finished touch through to the game one at a time, so menus move on while you record. While one is
     *  being sent the layer turns clear and lets touches through (they aren't recorded): wait for the red tint to return. */
    private fun startForwarder() {
        val q = Channel<MacroStroke>(Channel.UNLIMITED)
        fwdQueue = q
        fwdJob = svc.scope.launch {
            for (s in q) {
                setRecTouchable(false)
                try {
                    dispatchStroke(s, 1f, 1f, 1f)
                    delay(40)
                } finally {
                    setRecTouchable(true)
                }
            }
        }
    }

    private fun setRecTouchable(on: Boolean) {
        val v = recView ?: return
        val lp = recLp ?: return
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        v.setBackgroundColor(if (on) Color.argb(34, 255, 60, 60) else Color.TRANSPARENT)
        try { svc.wm.updateViewLayout(v, lp) } catch (_: Exception) { }
    }

    // ------------------------------------------------------------------ playback

    fun startPlay(loop: Boolean, quiet: Boolean = false) {
        if (mode != Mode.IDLE) return
        val m = MacroStore.load(svc)
        if (m == null) {
            if (!quiet) svc.toast("Record something first: tap \u25CF and do your touches")
            return
        }
        if (!canTouch()) {
            if (!quiet) svc.toast("Turn the screen on first")
            return
        }
        val (sw, sh) = svc.screenSize()
        if ((sw > sh) != (m.width > m.height)) {
            svc.toast("Rotate the phone to match the recording (it was recorded in ${if (m.width > m.height) "landscape" else "portrait"})")
            return
        }
        svc.stopClickerForMacro()
        mode = Mode.PLAYING
        looping = loop
        stopReason = null
        Prefs.setMacroLooping(svc, loop)
        watchScreenOff(true)
        setKeepScreen(true)
        refreshButtons()
        val id = ++runId
        playJob = svc.scope.launch {
            try {
                runPlayback(m, loop)
            } finally {
                if (id == runId) finishPlayback(null)
            }
        }
        if (!quiet) {
            svc.toast(
                if (loop) "Looping: waits ${Prefs.macroGapSec(svc)} s between runs. Tap \u25A0 to stop."
                else "Playing once. Tap \u25A0 to stop.",
            )
        }
    }

    private suspend fun runPlayback(m: Macro, loop: Boolean) {
        var n = 0
        while (true) {
            n++
            if (!playOnce(m, n, loop)) return
            if (!loop) return
            val gapMs = Prefs.macroGapSec(svc).coerceIn(0, 600) * 1000L
            val endAt = SystemClock.elapsedRealtime() + gapMs
            while (true) {
                val left = endAt - SystemClock.elapsedRealtime()
                if (left <= 0) break
                if (!canTouch()) { stopReason = "Screen is off: macro stopped"; return }
                setStatus("\uD83D\uDD01 #$n done\nnext in ${(left + 999) / 1000}s")
                delay(minOf(left, 250L))
            }
        }
    }

    /** Plays the whole recording once. Returns false if it had to stop early (screen off). */
    private suspend fun playOnce(m: Macro, n: Int, loop: Boolean): Boolean {
        val speed = Prefs.macroSpeed(svc).coerceIn(0.25f, 4f)
        val (sw, sh) = svc.screenSize()
        val sx = sw / m.width.toFloat()
        val sy = sh / m.height.toFloat()
        val t0 = SystemClock.elapsedRealtime()
        val total = m.strokes.size
        for ((i, s) in m.strokes.withIndex()) {
            val due = t0 + (s.startMs / speed).toLong()
            if (svc.hasShield()) {
                // open the shield's touch hole just before the stroke is due, so waiting time stays fully protected
                val openAt = due - HOLE_LEAD_MS
                val w1 = openAt - SystemClock.elapsedRealtime()
                if (w1 > 0) delay(w1)
                if (!canTouch()) { stopReason = "Screen is off: macro stopped"; return false }
                holeOpen(true)
            }
            val w2 = due - SystemClock.elapsedRealtime()
            if (w2 > 0) delay(w2)
            if (!canTouch()) { stopReason = "Screen is off: macro stopped"; return false }
            setStatus((if (loop) "\uD83D\uDD01 #$n\n" else "\u25B6 playing\n") + "${i + 1}/$total")
            dispatchStroke(s, sx, sy, speed)
            if (svc.hasShield()) {
                val next = m.strokes.getOrNull(i + 1)
                val keepOpen = next != null && (t0 + (next.startMs / speed).toLong()) - SystemClock.elapsedRealtime() < 400
                if (!keepOpen) {
                    delay(30)
                    holeOpen(false)
                }
            }
        }
        holeOpen(false)
        return true
    }

    private fun holeOpen(open: Boolean) {
        if (hole == open) return
        hole = open
        svc.setShieldTouchable(open)
    }

    private fun finishPlayback(msg: String?) {
        if (mode != Mode.PLAYING) return
        mode = Mode.IDLE
        looping = false
        Prefs.setMacroLooping(svc, false)
        hole = true   // force the next call to really close it
        holeOpen(false)
        watchScreenOff(false)
        setKeepScreen(false)
        refreshButtons()
        setStatus(idleStatus())
        val text = msg ?: stopReason
        stopReason = null
        if (text != null) svc.toast(text)
    }

    // ------------------------------------------------------------------ gestures

    private fun buildGesture(s: MacroStroke, sx: Float, sy: Float, speed: Float): GestureDescription? {
        val pts = s.pts
        if (pts.isEmpty()) return null
        val (sw, sh) = svc.screenSize()
        fun cx(v: Float) = (v * sx).coerceIn(1f, (sw - 1).toFloat())
        fun cy(v: Float) = (v * sy).coerceIn(1f, (sh - 1).toFloat())
        val p0 = pts[0]
        val path = Path()
        path.moveTo(cx(p0.x), cy(p0.y))
        // A tap or a hold stays on one spot. A swipe/drag follows the recorded path.
        val moved = pts.any { hypot(it.x - p0.x, it.y - p0.y) > MOVE_SLOP_PX }
        if (moved) {
            val step = max(1, pts.size / 48)
            var i = step
            while (i < pts.size - 1) {
                path.lineTo(cx(pts[i].x), cy(pts[i].y))
                i += step
            }
            val last = pts.last()
            path.lineTo(cx(last.x), cy(last.y))
        }
        val dur = (s.durationMs / speed).toLong().coerceIn(10L, GestureDescription.getMaxGestureDuration())
        return try {
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build()
        } catch (_: Exception) {
            null
        }
    }

    /** Sends one stroke and waits until it has finished (or a safety timeout). One quick retry if the system was busy. */
    private suspend fun dispatchStroke(s: MacroStroke, sx: Float, sy: Float, speed: Float) {
        val g = buildGesture(s, sx, sy, speed) ?: return
        try {
            for (attempt in 0..1) {
                val done = CompletableDeferred<Unit>()
                val ok = svc.dispatchGesture(
                    g,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) { done.complete(Unit) }
                        override fun onCancelled(gestureDescription: GestureDescription?) { done.complete(Unit) }
                    },
                    null,
                )
                if (ok) {
                    withTimeoutOrNull((s.durationMs / speed).toLong() + 1500L) { done.await() }
                    return
                }
                delay(120)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // one bad touch must never take the macro (or the app) down
        }
    }

    // ------------------------------------------------------------------ screen off = stop

    private fun watchScreenOff(on: Boolean) {
        if (on) {
            if (screenRx != null) return
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    svc.main.post { if (mode != Mode.IDLE) stopAll("Screen turned off: macro stopped") }
                }
            }
            try {
                val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
                if (Build.VERSION.SDK_INT >= 33) svc.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
                else svc.registerReceiver(r, f)
                screenRx = r
            } catch (_: Exception) { }
        } else {
            screenRx?.let { try { svc.unregisterReceiver(it) } catch (_: Exception) { } }
            screenRx = null
        }
    }

    // ------------------------------------------------------------------ the recording layer (draws your finger's trail)

    private class TrailView(context: Context) : View(context) {
        private val path = Path()
        private var fx = -1f
        private var fy = -1f
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 7f
            color = Color.argb(230, 56, 208, 255)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 255, 80, 80) }

        fun begin(x: Float, y: Float) { path.reset(); path.moveTo(x, y); fx = x; fy = y; invalidate() }
        fun moveTo(x: Float, y: Float) { path.lineTo(x, y); fx = x; fy = y; invalidate() }
        fun end() { path.reset(); fx = -1f; fy = -1f; invalidate() }

        override fun onDraw(canvas: Canvas) {
            if (fx >= 0f) {
                canvas.drawPath(path, line)
                canvas.drawCircle(fx, fy, 30f, dot)
            }
        }
    }
}
