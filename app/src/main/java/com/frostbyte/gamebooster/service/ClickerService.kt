package com.frostbyte.gamebooster.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
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
    private var shieldBlack = false
    private var shieldPill: TextView? = null
    private var shieldGravityStep = 0

    private var netCb: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var lostNet = false

    private val accent = Color.parseColor("#38D0FF")

    // ------------------------------------------------------------------ service lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        instance = this
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

    private fun startClicking() {
        if (running) return
        val active = ClickerStore.all(this).filter { it.enabled && it.placed }
        if (active.isEmpty()) {
            toast("Turn on a clicker in FrostByte and place its marker first")
            return
        }
        running = true
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
        toast("Auto clicker ON")
    }

    private fun stopClicking() {
        if (!running && jobs.isEmpty()) return
        running = false
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
        box.addView(btn("\uD83C\uDF11") { toggleShield(true) }, LinearLayout.LayoutParams(dp(46), dp(46)))
        box.addView(btn("\uD83D\uDEE1") { toggleShield(false) }, LinearLayout.LayoutParams(dp(46), dp(46)))
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

    // ------------------------------------------------------------------ screen shield (black "screen off" / clear touch shield)

    private fun toggleShield(black: Boolean) {
        val had = shield != null
        val sameKind = had && shieldBlack == black
        removeShield()
        if (sameKind) return
        addShield(black)
    }

    private fun addShield(black: Boolean) {
        val root = FrameLayout(this)
        root.setBackgroundColor(if (black) Color.BLACK else Color.argb(1, 0, 0, 0))
        root.setOnTouchListener { _, _ -> true }   // swallow ghost touches

        val unlock = Runnable { removeShield() }
        val p = TextView(this).apply {
            text = "\uD83D\uDD12 hold to wake"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.argb(if (black) 70 else 150, 255, 255, 255))   // very dim: friendly to AMOLED
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> main.postDelayed(unlock, 1200)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> main.removeCallbacks(unlock)
                }
                true
            }
        }
        root.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(12) })
        shieldPill = p

        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            overlayFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON), PixelFormat.TRANSLUCENT,
        )
        if (black) lp.screenBrightness = 0.01f   // dim backlight; black pixels are off on AMOLED
        try {
            wm.addView(root, lp)
            shield = root
            shieldBlack = black
            main.postDelayed(movePill, 60_000)
            toast(if (black) "Screen-off mode: clickers keep working. Hold the faint pill to wake." else "Touch shield on: ghost touches are blocked. Hold the pill to turn off.")
        } catch (_: Exception) {
            toast("Couldn't start the shield")
        }
    }

    // keep the pill moving so nothing stays lit in one spot on an AMOLED screen
    private val movePill = object : Runnable {
        override fun run() {
            val p = shieldPill ?: return
            val gravities = intArrayOf(
                Gravity.TOP or Gravity.CENTER_HORIZONTAL, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                Gravity.CENTER_VERTICAL or Gravity.START, Gravity.CENTER_VERTICAL or Gravity.END,
            )
            shieldGravityStep = (shieldGravityStep + 1) % gravities.size
            (p.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.gravity = gravities[shieldGravityStep]
                p.layoutParams = it
            }
            main.postDelayed(this, 60_000)
        }
    }

    private fun setShieldTouchable(passThrough: Boolean) {
        val s = shield ?: return
        val lp = s.layoutParams as? WindowManager.LayoutParams ?: return
        lp.flags = if (passThrough) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        try { wm.updateViewLayout(s, lp) } catch (_: Exception) { }
    }

    private fun removeShield() {
        main.removeCallbacks(movePill)
        shield?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        shield = null
        shieldPill = null
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }
}
