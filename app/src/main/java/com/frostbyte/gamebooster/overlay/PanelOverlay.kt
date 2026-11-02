package com.frostbyte.gamebooster.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.frostbyte.gamebooster.R
import com.frostbyte.gamebooster.data.Effective
import com.frostbyte.gamebooster.data.GameProfile
import com.frostbyte.gamebooster.data.GameTips
import com.frostbyte.gamebooster.data.Prefs
import com.frostbyte.gamebooster.service.CaptureService
import com.frostbyte.gamebooster.system.DeviceStats
import com.frostbyte.gamebooster.system.NetworkProbe
import com.frostbyte.gamebooster.system.Perms
import com.frostbyte.gamebooster.system.ThermalLevel
import com.frostbyte.gamebooster.util.Estimates
import com.frostbyte.gamebooster.util.Fmt
import com.frostbyte.gamebooster.util.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

private fun withA(c: Int, a: Int): Int = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private object C {
    val cyan = Color.parseColor("#38D0FF")
    val text = Color.parseColor("#EAF6FF")
    val muted = Color.parseColor("#8DA6BD")
    val good = Color.parseColor("#3DDC84")
    val warm = Color.parseColor("#FFC94D")
    val hot = Color.parseColor("#FF8A3D")
    val crit = Color.parseColor("#FF4D5E")
    val bg = Color.parseColor("#050B16")
}

/**
 * The right-side translucent gaming panel. Plain Android Views (not Compose) so it's light on a Galaxy A7 2018.
 * Slides in from the right, game stays visible behind it, swipe right or tap outside to close.
 */
class PanelOverlay(base: Context, private val cb: Callbacks) {

    interface Callbacks {
        val gameName: String
        val gameId: String
        val sessionStartMs: Long
        val startBattery: Int?
        fun profile(): GameProfile
        fun effective(): Effective
        fun setProfile(p: GameProfile)
        fun setBlock(e: Effective)
        fun lockTouch()
        fun stopGaming()
        fun openApp()
        fun afk(): Boolean
        fun toggleAfk()
        fun capture(mode: String)
    }

    private val ctx: Context = ContextThemeWrapper(base, android.R.style.Theme_DeviceDefault)
    private val wm = base.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pingJob: Job? = null
    private var accent: Int = Color.parseColor("#38D0FF")
    private var cardColor: Int = Color.parseColor("#122238")
    private var btnRec: TextView? = null
    private var btnAfk: TextView? = null

    private var root: FrameLayout? = null
    private var panel: SwipeLayout? = null
    private var panelW = 0
    private var closing = false
    private var syncing = false
    private val pings = ArrayList<Int?>()

    // live views
    private lateinit var tvMode: TextView
    private lateinit var tvProfileLine: TextView
    private lateinit var tvPerfStatus: TextView
    private lateinit var tvTip: TextView
    private lateinit var tvPowerTip: TextView
    private lateinit var tvHeat: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvThermal: TextView
    private lateinit var tvMem: TextView
    private lateinit var tvMemSub: TextView
    private lateinit var tvBatt: TextView
    private lateinit var tvEst: TextView
    private lateinit var tvBattSub: TextView
    private lateinit var barBatt: ProgressBar
    private lateinit var tvNet: TextView
    private lateinit var tvNetSub: TextView
    private lateinit var tvDndHint: TextView
    private lateinit var swNotif: Switch
    private lateinit var swCalls: Switch
    private lateinit var swAwake: Switch
    private val chips = LinkedHashMap<GameProfile, TextView>()

    val isShowing: Boolean get() = root != null

    // ---------------------------------------------------------------- show / hide

    fun show(): Boolean {
        if (root != null) return true
        val dm = ctx.resources.displayMetrics
        val landscape = dm.widthPixels > dm.heightPixels
        panelW = if (landscape) minOf((dm.widthPixels * 0.46f).toInt(), ctx.dp(380)) else (dm.widthPixels * 0.9f).toInt()

        val r = FrameLayout(ctx)
        r.setBackgroundColor(Color.argb(38, 0, 0, 0))     // game stays clearly visible
        r.setOnClickListener { hide() }

        val p = buildPanel()
        r.addView(p, FrameLayout.LayoutParams(panelW, MATCH, Gravity.END).apply {
            setMargins(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
        })

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val lp = WindowManager.LayoutParams(MATCH, MATCH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)
        if (Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled) {
            // Real blur only where the phone supports it (Android 12+); Android 10 uses plain transparency.
            flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            lp.flags = flags
            lp.blurBehindRadius = 14
        }

        try {
            wm.addView(r, lp)
        } catch (_: Exception) {
            Toast.makeText(ctx, "Couldn't open the panel. Check 'Display over other apps'.", Toast.LENGTH_LONG).show()
            return false
        }
        root = r
        panel = p
        closing = false

        refresh()
        val dur = if (Prefs.reducedMotion(ctx)) 0L else 250L
        if (dur == 0L) {
            p.translationX = 0f
        } else {
            p.translationX = (panelW + ctx.dp(16)).toFloat()
            p.animate().translationX(0f).setDuration(dur).setInterpolator(DecelerateInterpolator()).withLayer().start()
        }
        handler.postDelayed(ticker, 2000)
        startPing()
        return true
    }

    fun hide() {
        val r = root ?: return
        if (closing) return
        closing = true
        handler.removeCallbacks(ticker)
        pingJob?.cancel()
        val p = panel
        if (p == null || Prefs.reducedMotion(ctx)) {
            removeNow(r)
        } else {
            p.animate().translationX((panelW + ctx.dp(16)).toFloat()).setDuration(220)
                .setInterpolator(AccelerateInterpolator()).withLayer().withEndAction { removeNow(r) }.start()
        }
    }

    private fun removeNow(r: View) {
        try { wm.removeView(r) } catch (_: Exception) { }
        root = null
        panel = null
        closing = false
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        pingJob?.cancel()
        panel?.animate()?.cancel()
        root?.let { removeNow(it) }
        scope.cancel()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (root == null || closing) return
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    private fun startPing() {
        pingJob?.cancel()
        pings.clear()
        if (!Prefs.pingEnabled(ctx)) return
        pingJob = scope.launch {
            while (isActive) {
                pings.add(NetworkProbe.pingMs())
                if (pings.size > 5) pings.removeAt(0)
                delay(5000)
            }
        }
    }

    // ---------------------------------------------------------------- build

    private fun buildPanel(): SwipeLayout {
        accent = Prefs.panelAccent(ctx)
        cardColor = Prefs.panelCard(ctx)
        val bgc = Prefs.panelBg(ctx)
        val alpha = (Prefs.opacity(ctx) * 255).toInt().coerceIn(90, 250)
        val cardAlpha = (alpha * 0.55f).toInt()
        val shown = Prefs.panelCards(ctx)
        val sc = Prefs.shortcuts(ctx)

        val p = SwipeLayout(ctx) { hide() }
        p.background = GradientDrawable().apply {
            setColor(withA(bgc, alpha))
            cornerRadius = ctx.dp(22).toFloat()
            setStroke(ctx.dp(1), withA(accent, 110))
        }
        p.clipToOutline = true
        p.elevation = ctx.dp(10).toFloat()

        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val col = box().apply { setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(14)) }

        // header
        val header = box(false).apply { gravity = Gravity.CENTER_VERTICAL }
        val logo = ImageView(ctx).apply { setImageResource(R.drawable.ic_logo_small) }
        header.addView(logo, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)))
        val titles = box().apply { setPadding(ctx.dp(10), 0, 0, 0) }
        titles.addView(tv("FrostByte Game Booster", 14f, C.text, true))
        tvMode = tv("\u25CF GAMING MODE ACTIVE", 10f, C.good, true)
        titles.addView(tvMode)
        header.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        header.addView(iconBtn("\u2699") { cb.openApp() })
        header.addView(iconBtn("\u2715") { hide() })
        col.addView(header)

        col.addView(tv(cb.gameName.uppercase(), 20f, accent, true), lpTop(10))

        // performance (views always exist so refresh() is safe; only attached if enabled)
        val perf = card(cardAlpha)
        val perfHead = box(false)
        perfHead.addView(tv("Performance", 13f, C.muted, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        tvPerfStatus = tv("GOOD", 12f, C.good, true)
        perfHead.addView(tvPerfStatus)
        perf.addView(perfHead)
        tvProfileLine = tv("Gaming profile active", 12f, C.text)
        perf.addView(tvProfileLine, lpTop(2))
        val chipRow = box(false)
        chips.clear()
        GameProfile.entries.forEach { gp ->
            val c = chip(when (gp) { GameProfile.PERFORMANCE -> "Perf"; GameProfile.BALANCED -> "Balanced"; GameProfile.BATTERY -> "Battery"; GameProfile.CUSTOM -> "Custom" }) {
                cb.setProfile(gp)
                refresh()
            }
            chips[gp] = c
            chipRow.addView(c, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = ctx.dp(4) })
        }
        perf.addView(chipRow, lpTop(8))
        tvTip = tv("", 11f, C.muted)
        perf.addView(tvTip, lpTop(6))
        tvPowerTip = tv("Tip: turn on Android Battery Saver (tap)", 11f, accent, true).apply {
            visibility = View.GONE
            setOnClickListener { Perms.openBatterySaver(ctx) }
        }
        perf.addView(tvPowerTip, lpTop(4))
        tvHeat = tv("", 12f, C.hot, true).apply { visibility = View.GONE }
        perf.addView(tvHeat, lpTop(6))
        if ("perf" in shown) col.addView(perf, lpTop(10))

        // thermal + memory
        val thermal = card(cardAlpha)
        thermal.addView(tv("\uD83C\uDF21 Thermal Guard", 11f, C.muted, true))
        tvTemp = tv("-", 22f, C.text, true)
        thermal.addView(tvTemp)
        tvThermal = tv("", 12f, C.good, true)
        thermal.addView(tvThermal)
        val mem = card(cardAlpha)
        mem.addView(tv("\uD83E\uDDE0 Memory", 11f, C.muted, true))
        tvMem = tv("-", 22f, C.text, true)
        mem.addView(tvMem)
        tvMemSub = tv("", 12f, C.muted)
        mem.addView(tvMemSub)
        val tm = listOfNotNull(thermal.takeIf { "thermal" in shown }, mem.takeIf { "mem" in shown })
        if (tm.isNotEmpty()) {
            val row = box(false)
            tm.forEachIndexed { i, v ->
                row.addView(v, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    if (tm.size == 2) { if (i == 0) rightMargin = ctx.dp(4) else leftMargin = ctx.dp(4) }
                })
            }
            col.addView(row, lpTop(8))
        }

        // battery
        val batt = card(cardAlpha)
        batt.addView(tv("\uD83D\uDD0B Battery", 11f, C.muted, true))
        val battRow = box(false).apply { gravity = Gravity.BOTTOM }
        tvBatt = tv("-", 26f, C.text, true)
        battRow.addView(tvBatt)
        tvEst = tv("", 13f, C.muted).apply { setPadding(ctx.dp(10), 0, 0, ctx.dp(4)) }
        battRow.addView(tvEst)
        batt.addView(battRow)
        barBatt = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(C.good)
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(80, 141, 166, 189))
        }
        batt.addView(barBatt, LinearLayout.LayoutParams(MATCH, ctx.dp(6)).apply { topMargin = ctx.dp(4) })
        tvBattSub = tv("", 11f, C.muted)
        batt.addView(tvBattSub, lpTop(4))
        if ("batt" in shown) col.addView(batt, lpTop(8))

        // network
        val net = card(cardAlpha)
        net.addView(tv("\uD83D\uDCF6 Network", 11f, C.muted, true))
        tvNet = tv("-", 15f, C.text, true)
        net.addView(tvNet)
        tvNetSub = tv("", 12f, C.muted)
        net.addView(tvNetSub)
        if ("net" in shown) col.addView(net, lpTop(8))

        // block during game
        val block = card(cardAlpha)
        block.addView(tv("BLOCK DURING GAME", 11f, C.muted, true))
        tvDndHint = tv("Do Not Disturb access is off. Grant it in FrostByte > Settings to block interruptions.", 11f, C.warm).apply { visibility = View.GONE }
        block.addView(tvDndHint, lpTop(4))
        swNotif = Switch(ctx); swCalls = Switch(ctx); swAwake = Switch(ctx)
        block.addView(switchRow("\uD83D\uDD15  Block notifications", swNotif), lpTop(6))
        block.addView(switchRow("\uD83D\uDCDE  Block calls (alarms only)", swCalls), lpTop(2))
        block.addView(switchRow("\u2600  Prevent screen timeout", swAwake), lpTop(2))
        if ("block" in shown) col.addView(block, lpTop(8))

        // shortcuts (you choose which ones in Settings)
        val items = mutableListOf<TextView>()
        btnRec = null
        btnAfk = null
        if ("lock" in sc) items += button("\uD83D\uDD12 Touch Lock", true) { cb.lockTouch() }
        if ("shot" in sc) items += button("\uD83D\uDCF8 Screenshot") { cb.capture("shot") }
        if ("rec" in sc) {
            val b = button("\uD83C\uDFA5 Record") {
                if (CaptureService.recording) CaptureService.stopRecording(ctx) else cb.capture("rec")
            }
            btnRec = b
            items += b
        }
        if ("afk" in sc) {
            val b = button("\uD83C\uDF19 AFK") { cb.toggleAfk(); refresh() }
            btnAfk = b
            items += b
        }
        if ("vol" in sc) items += button("\uD83D\uDD0A Volume") {
            (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
        }
        if ("net" in sc) items += button("\uD83D\uDCF6 Network") {
            hide()
            Perms.open(ctx, Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
        }
        if ("settings" in sc) items += button("\u2699 Settings") { cb.openApp() }
        items.chunked(2).forEachIndexed { idx, pair ->
            val r = box(false)
            pair.forEachIndexed { i, v ->
                r.addView(v, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    if (pair.size == 2) { if (i == 0) rightMargin = ctx.dp(4) else leftMargin = ctx.dp(4) }
                })
            }
            col.addView(r, lpTop(if (idx == 0) 10 else 8))
        }
        col.addView(button("STOP GAMING MODE", false, C.crit) { cb.stopGaming() }, lpTop(8))

        scroll.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        p.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        return p
    }

    // ---------------------------------------------------------------- refresh (real values only)

    private fun colorFor(t: ThermalLevel?): Int = when (t) {
        null -> C.muted
        ThermalLevel.COOL -> C.good
        ThermalLevel.WARM -> C.warm
        ThermalLevel.HOT -> C.hot
        ThermalLevel.CRITICAL -> C.crit
    }

    private fun refresh() {
        if (root == null) return
        val b = DeviceStats.battery(ctx)
        val m = DeviceStats.memory(ctx)
        val th = b.thermal
        val profile = cb.profile()
        val elapsed = System.currentTimeMillis() - cb.sessionStartMs

        tvMode.text = "\u25CF GAMING MODE ACTIVE  \u00B7  ${Fmt.duration(elapsed)}" + if (cb.afk()) "  \u00B7  AFK" else ""
        btnRec?.text = if (CaptureService.recording) "\u23F9 Stop rec" else "\uD83C\uDFA5 Record"
        btnAfk?.text = if (cb.afk()) "\uD83C\uDF19 AFK: ON" else "\uD83C\uDF19 AFK"

        // performance
        val status = when {
            th == ThermalLevel.CRITICAL -> "CRITICAL"
            th == ThermalLevel.HOT -> "HOT"
            m.usedPercent >= 92 -> "LOW MEMORY"
            else -> "GOOD"
        }
        tvPerfStatus.text = status
        tvPerfStatus.setTextColor(if (status == "GOOD") C.good else if (status == "HOT" || status == "LOW MEMORY") C.hot else C.crit)
        tvProfileLine.text = "Gaming profile active \u00B7 ${profile.label}"
        chips.forEach { (gp, view) -> styleChip(view, gp == profile) }
        tvTip.text = GameTips.tip(cb.gameId, profile)
        tvPowerTip.visibility = if (profile == GameProfile.BATTERY && !DeviceStats.powerSave(ctx)) View.VISIBLE else View.GONE
        when (th) {
            ThermalLevel.HOT -> { tvHeat.text = "Device is getting hot. Lower graphics/FPS or take a break."; tvHeat.setTextColor(C.hot); tvHeat.visibility = View.VISIBLE }
            ThermalLevel.CRITICAL -> { tvHeat.text = "Critical heat. Stop gaming and let the device cool."; tvHeat.setTextColor(C.crit); tvHeat.visibility = View.VISIBLE }
            else -> tvHeat.visibility = View.GONE
        }

        // thermal
        tvTemp.text = b.tempC?.let { "%.0f\u00B0C".format(it) } ?: "-"
        tvThermal.text = th?.label ?: "Temperature information unavailable"
        tvThermal.setTextColor(colorFor(th))

        // memory
        tvMem.text = "%.1f GB".format(m.availBytes / 1_073_741_824.0)
        tvMemSub.text = "free \u00B7 ${m.usedPercent}% used"

        // battery
        tvBatt.text = b.percent?.let { "$it%" } ?: "N/A"
        barBatt.progress = b.percent ?: 0
        val est = Estimates.remainingMs(cb.startBattery, b.percent, elapsed, b.charging)
        tvEst.text = when {
            b.charging -> "Charging"
            est != null -> "~${Fmt.duration(est)} left"
            else -> "Measuring\u2026"
        }
        val saver = if (DeviceStats.powerSave(ctx)) "Battery Saver on" else "Battery Saver off"
        val start = cb.startBattery
        tvBattSub.text = buildString {
            append(saver)
            if (start != null && b.percent != null) append(" \u00B7 session ${start}% \u2192 ${b.percent}%")
        }

        // network
        tvNet.text = NetworkProbe.label(ctx)
        tvNetSub.text = when {
            !Prefs.pingEnabled(ctx) -> "Ping check is off (Settings)"
            pings.isEmpty() -> "Measuring ping\u2026"
            else -> {
                val last = pings.lastOrNull()
                val q = NetworkProbe.quality(pings)
                if (last == null) "Ping unavailable" else "Ping $last ms \u00B7 $q"
            }
        }

        // block switches
        val e = cb.effective()
        syncing = true
        swNotif.isChecked = e.blockNotifications
        swCalls.isChecked = e.blockCalls
        swAwake.isChecked = e.keepAwake
        syncing = false
        tvDndHint.visibility = if (Perms.dnd(ctx)) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- view helpers

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun box(vertical: Boolean = true): LinearLayout =
        LinearLayout(ctx).apply { orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }

    private fun lpTop(top: Int) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dp(top) }

    private fun card(alpha: Int): LinearLayout = box().apply {
        background = GradientDrawable().apply {
            setColor(withA(cardColor, alpha))
            cornerRadius = ctx.dp(14).toFloat()
            setStroke(ctx.dp(1), withA(accent, 60))
        }
        setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
    }

    private fun iconBtn(glyph: String, onClick: () -> Unit): TextView = tv(glyph, 18f, C.muted, true).apply {
        gravity = Gravity.CENTER
        setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6))
        setOnClickListener { onClick() }
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = tv(label, 11f, C.text, true).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(ctx.dp(2), ctx.dp(8), ctx.dp(2), ctx.dp(8))
        setOnClickListener { onClick() }
    }

    private fun styleChip(v: TextView, selected: Boolean) {
        v.setTextColor(if (selected) C.bg else C.text)
        v.background = GradientDrawable().apply {
            cornerRadius = ctx.dp(12).toFloat()
            setColor(if (selected) accent else withA(accent, 50))
        }
    }

    private fun button(label: String, primary: Boolean = false, tint: Int = accent, onClick: () -> Unit): TextView =
        tv(label, 13f, if (primary) C.bg else if (tint == C.crit) C.crit else C.text, true).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(8), ctx.dp(12), ctx.dp(8), ctx.dp(12))
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(14).toFloat()
                if (primary) {
                    setColor(accent)
                } else {
                    setColor(withA(tint, 45))
                    setStroke(ctx.dp(1), withA(tint, 90))
                }
            }
            setOnClickListener { onClick() }
        }

    private fun switchRow(label: String, sw: Switch): LinearLayout {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(accent, Color.parseColor("#6B7F93")))
        sw.trackTintList = ColorStateList(states, intArrayOf(withA(accent, 120), Color.argb(90, 107, 127, 147)))
        sw.setOnCheckedChangeListener { _, _ ->
            if (!syncing) {
                cb.setBlock(Effective(swNotif.isChecked, swCalls.isChecked, swAwake.isChecked))
                refresh()
            }
        }
        return box(false).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(tv(label, 13f, C.text), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(sw)
        }
    }

    /** Panel container that closes when dragged to the right. */
    private class SwipeLayout(ctx: Context, private val onDismiss: () -> Unit) : FrameLayout(ctx) {
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && dx > slop * 2 && abs(dx) > abs(dy) * 1.5f) dragging = true
                }
            }
            return dragging
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (dragging) translationX = max(0f, e.rawX - downX)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    dragging = false
                    if (translationX > width * 0.3f) onDismiss() else animate().translationX(0f).setDuration(150).start()
                }
            }
            return true
        }
    }
}
