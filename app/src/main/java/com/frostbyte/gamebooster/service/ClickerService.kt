package com.frostbyte.gamebooster.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.frostbyte.gamebooster.data.ClickTarget
import com.frostbyte.gamebooster.data.ClickerStore
import com.frostbyte.gamebooster.data.Prefs
import com.frostbyte.gamebooster.util.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Auto Clicker. An accessibility service used ONLY to perform taps at points you place. It declares no permission
 * to read screen content. Two clickers: repeat every N seconds, or tap N seconds after your internet comes back
 * (for Roblox's Reconnect button). A black "screen-off" shield keeps the game running while the display is dark.
 * While the shield is on, a wake guard instantly re-locks the screen if a ghost touch wakes the phone, and a
 * watchdog + saved session restore the shield and the taps if Android restarts the service overnight.
 */
class ClickerService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ClickerService? = null
            private set

        @Volatile
        var running: Boolean = false
            private set

        fun controlsVisible(): Boolean = instance?.controls != null

        /** Hold time to wake from the touch shield. Long on purpose so a ghost touch can't unlock it by accident. */
        private const val SHIELD_HOLD_MS = 5000L

        /** How long a shield session stays restorable after the service dies — covers an overnight AFK. */
        private const val ARMED_TTL_MS = 14L * 3600_000L

        /** Watchdog interval: how often a live session re-arms anything the system tore down. */
        private const val WATCHDOG_PERIOD_MS = 20_000L

        /** Minimum time between wake-guard re-locks, so a ghost touch storm can't hammer the system. */
        private const val GUARD_DEBOUNCE_MS = 300L
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = ArrayList<Job>()
    private lateinit var wm: WindowManager

    private var controls: View? = null
    private var controlsLp: WindowManager.LayoutParams? = null
    private var btnPlay: TextView? = null
    private val markers = HashMap<Int, View>()
    private var editing = true

    private var shield: FrameLayout? = null
    private var shieldPill: TextView? = null
    private var holdAnimator: android.animation.ValueAnimator? = null
    private var idlePulse: android.animation.ObjectAnimator? = null

    private var netCb: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var lostNet = false

    private var wakeGuard: BroadcastReceiver? = null
    private var watchdog: Job? = null
    @Volatile
    private var lastGuardLock = 0L
    private var shieldRetries = 0

    private val accent = Color.parseColor("#38D0FF")

    // ------------------------------------------------------------------ service lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        instance = this
        restoreSession()
    }

    /** If a shield session was armed and Android killed + rebound us while the screen is still off, bring the
     *  shield and the taps back instead of leaving the phone exposed to ghost touches all night. At boot the
     *  service only binds after the user unlocks, so a screen that's already on means a person is present: stay quiet. */
    private fun restoreSession() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val armedAt = Prefs.clickerArmedAt(this)
        val fresh = armedAt > 0 && System.currentTimeMillis() - armedAt < ARMED_TTL_MS
        if (!fresh || !Prefs.clickerArmed(this) || pm.isInteractive) return
        addShield()
        if (Prefs.clickerRunning(this)) startClicking(quiet = true)
        toast("FrostByte restored your AFK session")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* we never read the screen */ }

    override fun onInterrupt() { /* nothing to interrupt */ }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (!::wm.isInitialized) return
        hideAll()
        Prefs.setClickerArmed(this, false)   // deliberate teardown: don't auto-resume next time the service binds
        Prefs.setClickerRunning(this, false)
        scope.cancel()
        instance = null
    }

    // ------------------------------------------------------------------ public API (called from the FrostByte app)

    fun showControls() {
        if (controls == null) buildControls()
        editing = true
        refreshMarkers()
    }

    fun hideAll() {
        stopClicking()
        removeShield()
        markers.values.forEach { try { wm.removeView(it) } catch (_: Exception) { } }
        markers.clear()
        controls?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        controls = null
        controlsLp = null
        btnPlay = null
        editing = true
    }

    /** Re-draw the numbered markers from saved settings (enabled clickers only). */
    fun refreshMarkers() {
        if (controls == null) return
        markers.values.forEach { try { wm.removeView(it) } catch (_: Exception) { } }
        markers.clear()
        ClickerStore.all(this).filter { it.enabled }.forEach { addMarker(it) }
    }

    fun testTap(index: Int) {
        scope.launch {
            toast("Testing clicker ${index + 1} in 3 seconds")
            delay(3000)
            tapAt(ClickerStore.get(this@ClickerService, index))
        }
    }

    // ------------------------------------------------------------------ running

    private fun startClicking(quiet: Boolean = false) {
        if (running) return
        val active = ClickerStore.all(this).filter { it.enabled && it.placed }
        if (active.isEmpty()) {
            toast("Turn on a clicker in FrostByte and place its marker first")
            return
        }
        running = true
        Prefs.setClickerRunning(this, true)
        editing = false
        applyMarkerMode()
        registerNetwork()
        active.filter { it.mode == ClickTarget.MODE_REPEAT }.forEach { t ->
            jobs += scope.launch {
                while (isActive) {
                    delay(t.secRepeat * 1000L)
                    tapAt(t)
                }
            }
        }
        updatePlayButton()
        if (!quiet) toast("Auto clicker ON")
    }

    private fun stopClicking() {
        if (!running && jobs.isEmpty()) return
        running = false
        Prefs.setClickerRunning(this, false)
        jobs.forEach { it.cancel() }
        jobs.clear()
        unregisterNetwork()
        updatePlayButton()
    }

    private fun onNetworkBack() {
        if (!running) return
        ClickerStore.all(this).filter { it.enabled && it.placed && it.mode == ClickTarget.MODE_NETWORK }.forEach { t ->
            jobs += scope.launch {
                delay(t.secNet * 1000L)
                if (running) tapAt(t)
            }
        }
    }

    private fun registerNetwork() {
        if (netCb != null) return   // already watching; the watchdog restart path calls this again
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        lostNet = false
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) { lostNet = true }
            override fun onAvailable(network: Network) {
                if (lostNet) {
                    lostNet = false
                    main.post { onNetworkBack() }
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCb = cb
        } catch (_: Exception) {
            // no network permission or service; repeat-mode clickers still work
        }
    }

    private fun unregisterNetwork() {
        val cb = netCb ?: return
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
        } catch (_: Exception) { }
        netCb = null
    }

    /** One tap. The screen shield (if on) opens a tiny hole for ~150 ms so the tap reaches the game, then closes again. */
    private suspend fun tapAt(t: ClickTarget) {
        if (!t.placed) return
        val marker = markers[t.index]
        marker?.alpha = 1f
        setShieldTouchable(true)
        delay(60)
        val done = CompletableDeferred<Unit>()
        val path = Path().apply { moveTo(t.x.toFloat(), t.y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, t.holdMs.toLong().coerceAtLeast(1L))
        val sent = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { done.complete(Unit) }
                override fun onCancelled(gestureDescription: GestureDescription?) { done.complete(Unit) }
            },
            null,
        )
        if (sent) withTimeoutOrNull(1500) { done.await() }
        delay(40)
        setShieldTouchable(false)
        marker?.alpha = if (editing) 0.9f else 0.5f
    }

    // ------------------------------------------------------------------ overlays

    private fun overlayFlags(extra: Int = 0): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or extra

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private fun pill(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.argb(215, 6, 12, 24))
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), Color.argb(130, 56, 208, 255))
    }

    private fun buildControls() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill()
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun btn(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setOnClickListener { onClick() }
        }
        val size = LinearLayout.LayoutParams(dp(46), dp(46))
        val play = btn("\u25B6") { togglePlay() }
        btnPlay = play
        box.addView(play, size)
        box.addView(btn("\u270E") { toggleEdit() }, LinearLayout.LayoutParams(dp(46), dp(46)))
        box.addView(btn("\uD83D\uDEE1") { toggleShield() }, LinearLayout.LayoutParams(dp(46), dp(46)))
        box.addView(btn("\u2715") { hideAll() }, LinearLayout.LayoutParams(dp(46), dp(46)))

        val (sw, sh) = screenSize()
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, overlayFlags(), PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = sw - dp(64)
            y = sh / 5
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
                    try { wm.updateViewLayout(box, lp) } catch (_: Exception) { }
                }
            }
            true
        }
        box.addView(handle, LinearLayout.LayoutParams(dp(46), dp(46)))
        try {
            wm.addView(box, lp)
            controls = box
            controlsLp = lp
        } catch (_: Exception) {
            toast("Couldn't show the controls")
        }
    }

    private fun togglePlay() {
        if (running) stopClicking() else startClicking()
    }

    private fun toggleEdit() {
        if (running) stopClicking()
        editing = !editing
        applyMarkerMode()
        toast(if (editing) "Drag the numbered circles onto your buttons" else "Markers locked")
    }

    private fun updatePlayButton() {
        btnPlay?.text = if (running) "\u23F8" else "\u25B6"
        btnPlay?.setTextColor(if (running) Color.parseColor("#3DDC84") else Color.WHITE)
    }

    private fun addMarker(t: ClickTarget) {
        val size = dp(56)
        val (sw, sh) = screenSize()
        val cx = if (t.placed) t.x else sw / 2 + (t.index * dp(90))
        val cy = if (t.placed) t.y else sh / 2
        if (!t.placed) ClickerStore.setPos(this, t.index, cx, cy)

        val v = TextView(this).apply {
            text = "${t.index + 1}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FF8A3D"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(90, 30, 136, 255))
                setStroke(dp(3), accent)
            }
            alpha = if (editing) 0.9f else 0.5f
        }
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            overlayFlags(if (editing) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE), PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cx - size / 2
            y = cy - size / 2
        }
        var offX = 0f
        var offY = 0f
        v.setOnTouchListener { view, e ->
            if (!editing) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { offX = e.rawX - lp.x; offY = e.rawY - lp.y }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (e.rawX - offX).toInt()
                    lp.y = (e.rawY - offY).toInt()
                    try { wm.updateViewLayout(view, lp) } catch (_: Exception) { }
                }
                MotionEvent.ACTION_UP -> ClickerStore.setPos(this, t.index, lp.x + size / 2, lp.y + size / 2)
            }
            true
        }
        try {
            wm.addView(v, lp)
            markers[t.index] = v
        } catch (_: Exception) { }
    }

    /** Markers are draggable while editing, and see-through to taps while running. */
    private fun applyMarkerMode() {
        markers.values.forEach { v ->
            val lp = v.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            lp.flags = if (editing) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            v.alpha = if (editing) 0.9f else 0.5f
            try { wm.updateViewLayout(v, lp) } catch (_: Exception) { }
        }
    }

    // ------------------------------------------------------------------ touch shield (blocks ghost touches)

    private fun toggleShield() {
        if (shield != null) removeShield() else addShield()
    }

    private fun addShield() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.argb(1, 0, 0, 0))   // effectively invisible, but still blocks touches
        root.setOnTouchListener { _, _ -> true }   // swallow ghost touches

        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(170, 6, 12, 24))
            setStroke(dp(2), accent)
        }
        val p = TextView(this).apply {
            text = "\uD83D\uDD12  Hold 5s to unlock"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = ring
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> startHoldToUnlock(this)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelHoldToUnlock(this)
                }
                true
            }
        }
        root.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(16) })
        shieldPill = p
        startIdlePulse(p)

        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, overlayFlags(), PixelFormat.TRANSLUCENT,
        )
        try {
            wm.addView(root, lp)
            shield = root
            shieldRetries = 0
            Prefs.setClickerArmed(this, true)
            armWakeGuard()
            startWatchdog()
            toast("Touch shield on: ghost touches are blocked and the screen re-locks itself if a ghost touch wakes it. Hold the lock 5s to turn it off.")
        } catch (_: Exception) {
            toast("Couldn't start the shield")
        }
    }

    /** A small idle pulse so the pill reads as "alive" rather than a dead sticker. */
    private fun startIdlePulse(v: View) {
        idlePulse?.cancel()
        val a = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            v,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f),
        )
        a.duration = 1100
        a.repeatMode = android.animation.ValueAnimator.REVERSE
        a.repeatCount = android.animation.ValueAnimator.INFINITE
        a.start()
        idlePulse = a
    }

    /** Fills the ring over 5 real seconds; releasing early cancels it with no effect. */
    private fun startHoldToUnlock(v: TextView) {
        idlePulse?.cancel()
        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        val ring = v.background as? GradientDrawable ?: return
        holdAnimator?.cancel()
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
        anim.duration = SHIELD_HOLD_MS
        anim.addUpdateListener { a ->
            val t = a.animatedValue as Float
            ring.setStroke(dp(2) + (t * dp(4)).toInt(), accent)
            ring.setColor(Color.argb((170 + t * 70).toInt().coerceAtMost(255), 6, 12, 24))
            v.scaleX = 1f + t * 0.1f
            v.scaleY = 1f + t * 0.1f
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (holdAnimator === animation) removeShield()
            }
        })
        anim.start()
        holdAnimator = anim
    }

    private fun cancelHoldToUnlock(v: TextView) {
        holdAnimator?.cancel()
        holdAnimator = null
        val ring = v.background as? GradientDrawable
        ring?.setStroke(dp(2), accent)
        ring?.setColor(Color.argb(170, 6, 12, 24))
        v.scaleX = 1f
        v.scaleY = 1f
        startIdlePulse(v)
    }

    private fun setShieldTouchable(passThrough: Boolean) {
        val s = shield ?: return
        val lp = s.layoutParams as? WindowManager.LayoutParams ?: return
        lp.flags = if (passThrough) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try { wm.updateViewLayout(s, lp) } catch (_: Exception) { }
    }

    private fun removeShield() {
        holdAnimator?.cancel()
        holdAnimator = null
        idlePulse?.cancel()
        idlePulse = null
        watchdog?.cancel()
        watchdog = null
        disarmWakeGuard()
        Prefs.setClickerArmed(this, false)
        shield?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        shield = null
        shieldPill = null
    }

    // ------------------------------------------------------------------ wake guard + watchdog (keep an armed session alive)

    /** While the shield is on, any wake of a screen that was supposed to be asleep gets locked again instantly,
     *  using the accessibility lock action (no extra permission). A ghost touch can light the screen, but it can
     *  no longer swipe past the lock screen into the game. It re-locks on unlock too, in case an insecure keyguard
     *  gets walked through on an OEM that hides overlays from the lock screen. To use the phone for real, hold the
     *  shield pill 5s — that disarms this guard before you unlock. */
    private fun armWakeGuard() {
        if (wakeGuard != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (shield == null) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastGuardLock < GUARD_DEBOUNCE_MS) return   // don't hammer the system during a touch storm
                lastGuardLock = now
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(r, filter)
            wakeGuard = r
        } catch (_: Exception) { }
    }

    private fun disarmWakeGuard() {
        wakeGuard?.let { try { unregisterReceiver(it) } catch (_: Exception) { } }
        wakeGuard = null
    }

    /** Every so often, quietly re-arm anything the system tore down: the shield overlay itself and the tap loops. */
    private fun startWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive) {
                delay(WATCHDOG_PERIOD_MS)
                if (shield?.isAttachedToWindow != true) {
                    if (shieldRetries < 3) { shieldRetries++; removeShield(); addShield() }
                } else {
                    shieldRetries = 0
                }
                if (running && jobs.isEmpty()) {
                    running = false
                    startClicking(quiet = true)
                }
            }
        }
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }
}
