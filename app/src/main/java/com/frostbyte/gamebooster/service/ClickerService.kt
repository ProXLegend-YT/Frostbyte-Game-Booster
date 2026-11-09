package com.frostbyte.gamebooster.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
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
import androidx.core.app.NotificationCompat
import com.frostbyte.gamebooster.MainActivity
import com.frostbyte.gamebooster.R
import com.frostbyte.gamebooster.data.ClickTarget
import com.frostbyte.gamebooster.data.ClickerStore
import com.frostbyte.gamebooster.data.Prefs
import com.frostbyte.gamebooster.system.Perms
import com.frostbyte.gamebooster.system.RecentApps
import com.frostbyte.gamebooster.util.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
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
        private const val WATCHDOG_PERIOD_MS = 5_000L

        private const val CH_KEEP = "clicker_keepalive"
        private const val NOTIF_KEEP = 3

        /** Minimum time between wake-guard re-locks, so a ghost touch storm can't hammer the system. */
        private const val GUARD_DEBOUNCE_MS = 300L
    }

    internal val main = Handler(Looper.getMainLooper())
    // A tap loop must never be able to crash the whole app (a crash = the clicker vanishes mid-game).
    private val safety = CoroutineExceptionHandler { _, _ -> }
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main + safety)
    internal var scope = newScope()
    private var foregroundOn = false
    private var sessionOn = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val jobs = ArrayList<Job>()
    internal lateinit var wm: WindowManager

    /** The macro recorder/player ("Tiny Task"). Shares this service's overlays, shield and keep-alive. */
    val macro = MacroEngine(this)

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
        if (!scope.isActive) scope = newScope()   // the same object can be re-bound after a teardown
        instance = this
        restoreSession()
        main.post { recordLastExit() }   // a slow system call: never let it delay the restore
    }

    /** Android kills the whole FrostByte process under memory pressure (Roblox is heavy), then re-binds this service.
     *  Before, that left you with no controls. Now the saved session brings back the controls, the numbered markers,
     *  the taps and (only if the screen is off) the shield. After a reboot or 14 h it stays quiet. */
    private fun restoreSession() {
        val clicker = Prefs.clickerSession(this)
        val macroOn = Prefs.macroSession(this)
        if (!clicker && !macroOn) return
        // 1) Shield first: it is a single view and the most important thing to get back on screen quickly.
        //    AFK keeps the screen lit, so a lit screen proves nothing; the shield was armed on purpose.
        //    (A reboot is already ruled out by the session check; holding the pill 5 s still turns it off.)
        if (Prefs.clickerArmed(this)) addShield(quiet = true)
        // 2) Clicker controls + markers + taps.
        if (clicker) {
            sessionOn = true
            showControls()
            if (Prefs.clickerRunning(this)) startClicking(quiet = true)
        }
        // 3) Macro bar, and the loop if it was running.
        if (macroOn) macro.restore()
        bumpShield()   // the shield goes back on top of the controls it was covering before
        toast("FrostByte restored your ${if (macroOn && !clicker) "Macro recorder" else "Auto Clicker"}")
    }

    /** Android 11+ remembers why our process last died. Saved so the Auto Clicker screen can show the real cause. */
    private fun recordLastExit() {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val e = am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull() ?: return
            if (e.timestamp <= Prefs.lastExitTs(this)) return
            val why = when (e.reason) {
                ApplicationExitInfo.REASON_LOW_MEMORY -> "Android ran low on memory and killed FrostByte"
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "FrostByte crashed"
                ApplicationExitInfo.REASON_ANR -> "FrostByte froze (not responding)"
                ApplicationExitInfo.REASON_SIGNALED -> "Android killed FrostByte (usually memory pressure)"
                ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> "FrostByte was force-stopped"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Android stopped FrostByte for using too many resources"
                ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission was changed"
                ApplicationExitInfo.REASON_EXIT_SELF -> "FrostByte closed itself"
                else -> "stopped by the system (code ${e.reason})"
            }
            val extra = e.description?.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""
            Prefs.setLastExit(this, e.timestamp, "$why$extra")
        } catch (_: Exception) { }
    }

    private var lastTrimClear = 0L

    /** Android warns a foreground process before it starts killing. At the first warning, clear other apps' background
     *  processes (never a force-stop, never the app on screen) so Android frees RAM from them instead of from us. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < 10 || !(sessionOn || macro.barVisible) || !Prefs.boostFreeRam(this)) return   // 10 = RUNNING_LOW
        val now = SystemClock.elapsedRealtime()
        if (now - lastTrimClear < 60_000L) return
        lastTrimClear = now
        scope.launch(Dispatchers.Default) {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                RecentApps.packages(this@ClickerService, emptySet()).forEach {
                    try { am.killBackgroundProcesses(it) } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* we never read the screen */ }

    override fun onInterrupt() { /* nothing to interrupt */ }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // Still switched on in Settings = Android restarted us (keep the session so it comes back).
        // Switched off = the user turned it off on purpose (forget the session).
        teardown(forgetSession = !Perms.accessibility(this))
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown(forgetSession = false)
        super.onDestroy()
    }

    /** Removes everything we put on screen and stops all work. Only touches saved state when [forgetSession]. */
    private fun teardown(forgetSession: Boolean) {
        if (!::wm.isInitialized) return
        macro.teardown(forgetSession)
        stopClickingInternal(persist = false)
        removeShield(persist = false)
        removeAllViews()
        stopKeepAlive()
        releaseWake()
        sessionOn = false
        if (forgetSession) {
            Prefs.setClickerSession(this, false)
            Prefs.setClickerArmed(this, false)
            Prefs.setClickerRunning(this, false)
        }
        scope.cancel()
        instance = null
    }

    private fun removeAllViews() {
        if (!macro.barVisible) {
            watchdog?.cancel()
            watchdog = null
        }
        markers.values.forEach { try { wm.removeView(it) } catch (_: Exception) { } }
        markers.clear()
        controls?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        controls = null
        controlsLp = null
        btnPlay = null
        editing = true
    }

    // ------------------------------------------------------------------ keep-alive (foreground + wake lock)

    /** A foreground notification raises this process's priority, so Android kills it far later than a plain background
     *  service when a big game fills the RAM. This is the main thing that stops the clicker from disappearing. */
    internal fun startKeepAlive() {
        if (foregroundOn) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH_KEEP, "Auto Clicker (keeps it running)", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
            val open = PendingIntent.getActivity(
                this, 11, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n: Notification = NotificationCompat.Builder(this, CH_KEEP)
                .setSmallIcon(R.drawable.ic_stat_frost)
                .setContentTitle("FrostByte Auto Clicker")
                .setContentText("Controls are on \u00B7 this notification keeps the clicker alive while you play")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_KEEP, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIF_KEEP, n)
            foregroundOn = true
        } catch (_: Exception) {
            // refused (for example notifications blocked): the clicker still works, just less protected
        }
    }

    private fun stopKeepAlive() {
        if (!foregroundOn) return
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) { }
        foregroundOn = false
    }

    /** Keeps the CPU awake only while taps are scheduled, so timers don't get delayed when the display is dark. */
    private fun holdWake() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FrostByte:clicker").apply {
                setReferenceCounted(false)
                acquire(12L * 3600_000L)
            }
        } catch (_: Exception) { }
    }

    private fun releaseWake() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        wakeLock = null
    }

    // ------------------------------------------------------------------ helpers shared with the macro recorder

    internal fun hasShield(): Boolean = shield != null

    internal fun removeShieldForMacro() = removeShield(persist = true)

    /** Playing a macro and the Auto Clicker both tap the screen, so only one of them runs at a time. */
    internal fun stopClickerForMacro() {
        if (running) stopClicking()
    }

    /** Re-adds the shield on top of everything else, so controls added after it can never be pressed by a ghost touch. */
    internal fun bumpShield() {
        val sh = shield ?: return
        val lp = sh.layoutParams ?: return
        try {
            wm.removeView(sh)
            wm.addView(sh, lp)
        } catch (_: Exception) { }
    }

    /** The keep-alive notification stays while either the clicker controls or the macro bar are on screen. */
    internal fun maybeStopKeepAlive() {
        if (!sessionOn && !macro.barVisible) stopKeepAlive()
    }

    // ------------------------------------------------------------------ public API (called from the FrostByte app)

    fun showControls() {
        sessionOn = true
        Prefs.setClickerSession(this, true)
        if (controls == null) buildControls()
        editing = true
        refreshMarkers()
        startWatchdog()
        startKeepAlive()   // after the views are up, so the notification never delays them
    }

    /** The user pressed \u2715 / Hide: stop everything AND forget the session so it doesn't come back by itself. */
    fun hideAll() {
        stopClickingInternal(persist = true)
        if (!macro.barVisible) removeShield(persist = true)   // a shield the macro is using stays on
        sessionOn = false
        removeAllViews()
        maybeStopKeepAlive()
        releaseWake()
        Prefs.setClickerSession(this, false)
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
        if (macro.busy) macro.stopAll("Macro stopped")
        if (Prefs.fishMode(this)) { startFishing(quiet); return }
        val active = ClickerStore.all(this).filter { it.enabled && it.placed }
        if (active.isEmpty()) {
            toast("Turn on a clicker in FrostByte and place its marker first")
            return
        }
        running = true
        Prefs.setClickerRunning(this, true)
        editing = false
        holdWake()
        applyMarkerMode()
        registerNetwork()
        active.filter { it.mode == ClickTarget.MODE_REPEAT }.forEach { t ->
            jobs += scope.launch {
                // Fixed-rate schedule: the gap between taps doesn't grow by the time each tap takes.
                val gap = t.secRepeat * 1000L
                var next = SystemClock.elapsedRealtime() + gap
                while (isActive) {
                    val wait = next - SystemClock.elapsedRealtime()
                    if (wait > 0) delay(wait)
                    safeTap(t)
                    next += gap
                    val now = SystemClock.elapsedRealtime()
                    if (next < now) next = now + gap   // we fell behind (phone slept): don't fire a burst of taps
                }
            }
        }
        updatePlayButton()
        if (!quiet) toast("Auto clicker ON")
    }

    // ------------------------------------------------------------------ fishing loop (hold key + fast-tap key)

    /** Clicker 1 is the HOLD key (cast), Clicker 2 is the FAST-TAP key (reel). The loop is: hold, wait, tap fast, pause, repeat. */
    private fun startFishing(quiet: Boolean) {
        val hold = ClickerStore.get(this, 0)
        val tap = ClickerStore.get(this, 1)
        if (!hold.placed || !tap.placed) {
            toast("Fishing mode needs both markers placed: 1 = hold key, 2 = fast-tap key")
            return
        }
        running = true
        Prefs.setClickerRunning(this, true)
        editing = false
        holdWake()
        applyMarkerMode()
        jobs += scope.launch {
            while (isActive) {
                try {
                    holdAt(hold, Prefs.fishHoldMs(this@ClickerService).toLong())
                    delay(Prefs.fishWaitMs(this@ClickerService).toLong())
                    burstAt(tap, Prefs.fishRate(this@ClickerService), Prefs.fishBurstMs(this@ClickerService).toLong())
                    delay(Prefs.fishPauseMs(this@ClickerService).toLong().coerceAtLeast(300L))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    delay(1000)   // one bad round must never end the loop
                }
            }
        }
        updatePlayButton()
        if (!quiet) toast("Fishing loop ON: hold, wait, fast-tap, repeat")
    }

    /** Presses and holds one spot for [ms], then lets go. The shield (if on) stays open for the whole hold. */
    private suspend fun holdAt(t: ClickTarget, ms: Long) {
        val shielded = shield != null
        val marker = markers[t.index]
        marker?.alpha = 1f
        try {
            if (shielded) { setShieldTouchable(true); delay(60) }
            val path = Path().apply { moveTo(t.x.toFloat(), t.y.toFloat()) }
            val dur = ms.coerceIn(10L, GestureDescription.getMaxGestureDuration())
            sendGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build(), dur + 1500L)
            if (shielded) delay(40)
        } finally {
            if (shielded) setShieldTouchable(false)
            marker?.alpha = if (editing) 0.9f else 0.5f
        }
    }

    /** Taps one spot [rate] times a second for [totalMs]. Up to 20 taps go out in a single gesture, so the speed stays exact. */
    private suspend fun burstAt(t: ClickTarget, rate: Int, totalMs: Long) {
        val interval = 1000L / rate.coerceIn(1, 20)
        val tapMs = minOf(40L, maxOf(10L, interval - 8L))
        val count = maxOf(1L, totalMs / interval)
        val perGesture = GestureDescription.getMaxStrokeCount().coerceAtMost(20)
        val shielded = shield != null
        val marker = markers[t.index]
        marker?.alpha = 1f
        try {
            if (shielded) { setShieldTouchable(true); delay(60) }
            var done = 0L
            while (done < count) {
                val batch = minOf(perGesture.toLong(), count - done).toInt()
                val b = GestureDescription.Builder()
                for (i in 0 until batch) {
                    val path = Path().apply { moveTo(t.x.toFloat(), t.y.toFloat()) }
                    b.addStroke(GestureDescription.StrokeDescription(path, i * interval, tapMs))
                }
                sendGesture(b.build(), batch * interval + 1500L)
                done += batch
            }
            if (shielded) delay(40)
        } finally {
            if (shielded) setShieldTouchable(false)
            marker?.alpha = if (editing) 0.9f else 0.5f
        }
    }

    /** Sends a gesture and waits until it finishes (or a safety timeout). One quick retry if the system was busy. */
    private suspend fun sendGesture(g: GestureDescription, timeoutMs: Long) {
        for (attempt in 0..1) {
            val done = CompletableDeferred<Unit>()
            val ok = dispatchGesture(
                g,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { done.complete(Unit) }
                    override fun onCancelled(gestureDescription: GestureDescription?) { done.complete(Unit) }
                },
                null,
            )
            if (ok) { withTimeoutOrNull(timeoutMs) { done.await() }; return }
            delay(120)
        }
    }

    private fun stopClicking() = stopClickingInternal(persist = true)

    private fun stopClickingInternal(persist: Boolean) {
        if (!running && jobs.isEmpty()) return
        running = false
        if (persist) Prefs.setClickerRunning(this, false)
        jobs.forEach { it.cancel() }
        jobs.clear()
        unregisterNetwork()
        releaseWake()
        updatePlayButton()
    }

    private fun onNetworkBack() {
        if (!running) return
        ClickerStore.all(this).filter { it.enabled && it.placed && it.mode == ClickTarget.MODE_NETWORK }.forEach { t ->
            jobs += scope.launch {
                delay(t.secNet * 1000L)
                if (running) safeTap(t)
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

    private suspend fun safeTap(t: ClickTarget) {
        try {
            tapAt(t)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // one bad tap must never take the clicker (or the app) down
        }
    }

    /** One tap. The screen shield (if on) opens a tiny hole for ~150 ms so the tap reaches the game, then closes again.
     *  With no shield there is no waiting at all, so the tap lands the instant it is due. */
    private suspend fun tapAt(t: ClickTarget) {
        if (!t.placed) return
        val shielded = shield != null
        val marker = markers[t.index]
        marker?.alpha = 1f
        try {
            if (shielded) {
                setShieldTouchable(true)
                delay(60)
            }
            var sent = false
            for (attempt in 0..1) {
                val done = CompletableDeferred<Unit>()
                val path = Path().apply { moveTo(t.x.toFloat(), t.y.toFloat()) }
                val stroke = GestureDescription.StrokeDescription(path, 0, t.holdMs.toLong().coerceAtLeast(1L))
                sent = dispatchGesture(
                    GestureDescription.Builder().addStroke(stroke).build(),
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) { done.complete(Unit) }
                        override fun onCancelled(gestureDescription: GestureDescription?) { done.complete(Unit) }
                    },
                    null,
                )
                if (sent) { withTimeoutOrNull(1500) { done.await() }; break }
                delay(150)   // the system was busy: one quick retry
            }
            if (shielded) delay(40)
        } finally {
            if (shielded) setShieldTouchable(false)
            marker?.alpha = if (editing) 0.9f else 0.5f
        }
    }

    // ------------------------------------------------------------------ overlays

    internal fun overlayFlags(extra: Int = 0): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or extra

    @Suppress("DEPRECATION")
    internal fun screenSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    internal fun pill(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.argb(215, 6, 12, 24))
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), Color.argb(130, 56, 208, 255))
    }

    private fun buildControls(quiet: Boolean = false) {
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
            if (!quiet) toast("Couldn't show the controls")
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

    internal fun toggleShield() {
        if (shield != null) removeShield() else addShield()
    }

    private fun addShield(quiet: Boolean = false) {
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
            if (!quiet) shieldRetries = 0
            Prefs.setClickerArmed(this, true)
            armWakeGuard()
            startWatchdog()
            if (!quiet) toast("Touch shield on: ghost touches are blocked and the screen re-locks itself if a ghost touch wakes it. Hold the lock 5s to turn it off.")
        } catch (_: Exception) {
            if (!quiet) toast("Couldn't start the shield")
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

    internal fun setShieldTouchable(passThrough: Boolean) {
        val s = shield ?: return
        val lp = s.layoutParams as? WindowManager.LayoutParams ?: return
        lp.flags = if (passThrough) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try { wm.updateViewLayout(s, lp) } catch (_: Exception) { }
    }

    private fun removeShield(persist: Boolean = true) {
        holdAnimator?.cancel()
        holdAnimator = null
        idlePulse?.cancel()
        idlePulse = null
        disarmWakeGuard()
        if (persist) Prefs.setClickerArmed(this, false)
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

    /** Every few seconds, quietly put back anything the system tore down: the control bar, the numbered markers, the
     *  shield, and the tap loops. Cheap (a few view checks), runs only while the clicker session is on. */
    internal fun startWatchdog() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            while (isActive) {
                delay(WATCHDOG_PERIOD_MS)
                try { heal() } catch (_: Exception) { }
            }
        }
    }

    private fun heal() {
        if (!::wm.isInitialized) return
        macro.heal()
        if (!sessionOn && !macro.barVisible) return

        if (sessionOn) {
            val c = controls
            if (c == null || !c.isAttachedToWindow) {
                if (c != null) try { wm.removeView(c) } catch (_: Exception) { }
                controls = null
                controlsLp = null
                buildControls(quiet = true)
                updatePlayButton()
                bumpShield()
            }

            val wanted = ClickerStore.all(this).count { it.enabled }
            if (controls != null && (markers.size != wanted || markers.values.any { !it.isAttachedToWindow })) {
                refreshMarkers()
                applyMarkerMode()
            }
        }

        if (Prefs.clickerArmed(this)) {
            if (shield?.isAttachedToWindow != true) {
                if (shieldRetries < 3) { shieldRetries++; removeShield(persist = false); addShield(quiet = true) }
            } else {
                shieldRetries = 0
            }
        }

        if (sessionOn) {
            val needsLoop = Prefs.fishMode(this) || ClickerStore.all(this).any { it.enabled && it.placed && it.mode == ClickTarget.MODE_REPEAT }
            if (running && needsLoop && jobs.none { it.isActive }) {
                stopClickingInternal(persist = false)
                startClicking(quiet = true)
            }
        }
    }

    internal fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }
}
