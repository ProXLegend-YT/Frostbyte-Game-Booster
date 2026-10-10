package com.frostbyte.gamebooster.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.frostbyte.gamebooster.CaptureActivity
import com.frostbyte.gamebooster.MainActivity
import com.frostbyte.gamebooster.R
import com.frostbyte.gamebooster.data.Effective
import com.frostbyte.gamebooster.data.GameProfile
import com.frostbyte.gamebooster.data.InstalledGame
import com.frostbyte.gamebooster.data.NO_TEMP
import com.frostbyte.gamebooster.data.Prefs
import com.frostbyte.gamebooster.data.Profiles
import com.frostbyte.gamebooster.data.Session
import com.frostbyte.gamebooster.data.SessionStore
import com.frostbyte.gamebooster.overlay.PanelOverlay
import com.frostbyte.gamebooster.overlay.TouchLockOverlay
import com.frostbyte.gamebooster.system.BrightnessController
import com.frostbyte.gamebooster.system.DeviceStats
import com.frostbyte.gamebooster.system.DndController
import com.frostbyte.gamebooster.system.MemoryBoost
import com.frostbyte.gamebooster.system.Perms
import com.frostbyte.gamebooster.system.SyncController
import com.frostbyte.gamebooster.system.ThermalLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gaming Mode. Runs only while you play: shows the "Game Booster" notification, owns the right-side panel,
 * applies your Block-During-Game choices, records the session, and cleans up everything when it ends.
 */
class GamingService : Service() {

    companion object {
        const val ACTION_START = "com.frostbyte.gamebooster.START"
        const val ACTION_STOP = "com.frostbyte.gamebooster.STOP"
        const val ACTION_OPEN_PANEL = "com.frostbyte.gamebooster.OPEN_PANEL"
        const val ACTION_BOOST = "com.frostbyte.gamebooster.BOOST"
        const val ACTION_UNLOCK = "com.frostbyte.gamebooster.UNLOCK"
        const val ACTION_AFK = "com.frostbyte.gamebooster.AFK"
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_PKG = "pkg"
        private const val EXTRA_AFK = "afk"
        private const val CH_MAIN = "gaming_mode"
        private const val CH_ALERT = "thermal_alerts"
        private const val NOTIF_MAIN = 1
        private const val NOTIF_ALERT = 2

        /** Name of the game Gaming Mode is active for, or null. Read by the Home screen. */
        @Volatile
        var activeGameName: String? = null
            private set

        /** True while AFK mode is on, and when the current session started (for the AFK screen timer). */
        @Volatile
        var afkActive: Boolean = false
            private set

        @Volatile
        var activeStartMs: Long = 0L
            private set

        fun start(ctx: Context, game: InstalledGame, afk: Boolean = false) {
            val i = Intent(ctx, GamingService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_ID, game.def.id).putExtra(EXTRA_NAME, game.label).putExtra(EXTRA_PKG, game.packageName)
                .putExtra(EXTRA_AFK, afk)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun toggleAfk(ctx: Context) {
            ctx.startService(Intent(ctx, GamingService::class.java).setAction(ACTION_AFK))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, GamingService::class.java).setAction(ACTION_STOP))
        }
    }

    private var running = false
    private var gameId = ""
    private var gameName = ""
    private var gamePkg = ""
    private var startMs = 0L
    private var batStart: Int? = null
    private var tempStart: Float? = null
    private var tempMax: Float? = null
    private var lastThermal: ThermalLevel? = null
    private var lastGameSeen = 0L
    private var lastEventTime = 0L
    private var currentFg: String? = null
    private var locked = false
    private var afk = false
    private var afkLocked = false

    private var panel: PanelOverlay? = null
    private var lockOverlay: TouchLockOverlay? = null
    private var awakeView: View? = null
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (intent != null) startGaming(intent)
            ACTION_OPEN_PANEL -> if (running) openPanel() else stopSelf()
            ACTION_BOOST -> if (running) boostNow() else stopSelf()
            ACTION_UNLOCK -> if (running) unlockTouch() else stopSelf()
            ACTION_AFK -> if (running) setAfk(!afk, true) else stopSelf()
            ACTION_STOP -> stopGaming()
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ lifecycle

    private fun startGaming(intent: Intent) {
        createChannels()
        if (running) finishSession()   // switching games: close the previous session first
        gameId = intent.getStringExtra(EXTRA_ID).orEmpty()
        gameName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        gamePkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        running = true
        activeGameName = gameName
        startMs = System.currentTimeMillis()
        lastGameSeen = startMs
        lastEventTime = 0L
        currentFg = null
        locked = false
        afk = intent.getBooleanExtra(EXTRA_AFK, false)
        afkLocked = false
        afkActive = afk
        activeStartMs = startMs
        val b = DeviceStats.battery(this)
        batStart = b.percent
        tempStart = b.tempC
        tempMax = b.tempC
        lastThermal = b.thermal

        goForeground()
        applyBoost()
        startMonitor()
        if (Prefs.sweepEnabled(this)) {
            scope.launch {
                delay(10_000)
                if (running) MemoryBoost.sweep(this@GamingService, setOf(gamePkg))
            }
        }
        if (afk) setAfk(true, false)
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_MAIN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_MAIN, n)
        }
    }

    private fun stopGaming() {
        if (running) finishSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Ends the session: records history, removes overlays, restores the user's Do Not Disturb. Safe to call twice. */
    private fun finishSession() {
        running = false
        activeGameName = null
        val wasAfk = afk
        afk = false
        afkActive = false
        afkLocked = false
        monitorJob?.cancel()
        panel?.destroy()
        panel = null
        lockOverlay?.hide()
        lockOverlay = null
        locked = false
        setKeepAwake(false)
        DndController.restore(this)
        BrightnessController.restore(this)
        SyncController.restore(this)

        val end = System.currentTimeMillis()
        if (end - startMs >= 20_000) {
            val b = DeviceStats.battery(this)
            SessionStore.add(
                this,
                Session(gameId, if (wasAfk) "$gameName \u00B7 AFK" else gameName, startMs, end, batStart ?: -1, b.percent ?: -1,
                    tempStart ?: NO_TEMP, b.tempC ?: NO_TEMP, tempMax ?: NO_TEMP),
            )
        }
    }

    override fun onDestroy() {
        if (running) finishSession()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ boost (real, permission-gated actions only)

    private fun applyBoost(): String {
        val profile = Prefs.profile(this, gameId)
        val eff = Profiles.effective(this, profile)
        val dndOk = DndController.apply(this, eff.blockNotifications, eff.blockCalls)
        setKeepAwake(eff.keepAwake || afk)
        val parts = mutableListOf("${profile.label} profile active")
        if (eff.blockNotifications || eff.blockCalls) parts += if (dndOk) "Do Not Disturb on" else "Do Not Disturb needs permission"
        if (afk) parts += "AFK mode"
        if (eff.keepAwake || afk) parts += if (awakeView != null) "screen stays awake" else "screen-awake needs overlay permission"
        if (Prefs.lowerBrightness(this)) {
            parts += if (BrightnessController.apply(this, Prefs.brightnessPct(this))) "brightness ${Prefs.brightnessPct(this)}%" else "brightness needs 'Modify system settings'"
        } else {
            BrightnessController.restore(this)
        }
        if (Prefs.pauseSync(this)) {
            parts += if (SyncController.pause(this)) "background sync paused" else "sync pause failed"
        } else {
            SyncController.restore(this)
        }
        return parts.joinToString(" \u00B7 ")
    }

    /** Panel/notification "Boost": end background processes again + re-apply your gaming settings. */
    private fun boostNow() {
        scope.launch {
            val r = if (Prefs.sweepEnabled(this@GamingService)) MemoryBoost.sweep(this@GamingService, setOf(gamePkg)) else null
            withContext(Dispatchers.Main) {
                if (running) toast(applyBoost() + (r?.let { "\n" + it.summary() } ?: ""))
            }
        }
    }

    private fun setKeepAwake(on: Boolean) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (on && awakeView == null && Perms.overlay(this)) {
            // A 1-pixel invisible window with keepScreenOn: no wake lock needed, removed the moment gaming ends.
            val v = View(this).apply { keepScreenOn = true }
            val lp = WindowManager.LayoutParams(
                1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            )
            lp.gravity = Gravity.TOP or Gravity.START
            try {
                wm.addView(v, lp)
                awakeView = v
            } catch (_: Exception) {
                // overlay refused; reported via applyBoost()
            }
        } else if (!on) {
            awakeView?.let { try { wm.removeView(it) } catch (_: Exception) { } }
            awakeView = null
        }
    }

    // ------------------------------------------------------------------ panel + touch lock

    private val panelCb = object : PanelOverlay.Callbacks {
        override val gameName: String get() = this@GamingService.gameName
        override val gameId: String get() = this@GamingService.gameId
        override val sessionStartMs: Long get() = startMs
        override val startBattery: Int? get() = batStart
        override fun profile(): GameProfile = Prefs.profile(this@GamingService, this@GamingService.gameId)
        override fun effective(): Effective = Profiles.effective(this@GamingService, profile())
        override fun setProfile(p: GameProfile) {
            Prefs.setProfile(this@GamingService, this@GamingService.gameId, p)
            applyBoost()
        }
        override fun setBlock(e: Effective) {
            Prefs.setCustom(this@GamingService, e)
            Prefs.setProfile(this@GamingService, this@GamingService.gameId, GameProfile.CUSTOM)
            applyBoost()
        }
        override fun lockTouch() = this@GamingService.lockTouch()
        override fun stopGaming() = this@GamingService.stopGaming()
        override fun openApp() {
            panel?.hide()
            Perms.open(this@GamingService, Intent(this@GamingService, MainActivity::class.java))
        }
        override fun boost() = this@GamingService.boostNow()
        override fun afk(): Boolean = afk
        override fun toggleAfk() = setAfk(!afk, true)
        override fun capture(mode: String) {
            panel?.hide()
            // wait for the slide-out so the panel isn't in the shot
            main.postDelayed({
                Perms.open(this@GamingService, Intent(this@GamingService, CaptureActivity::class.java).putExtra(CaptureActivity.EXTRA_MODE, mode))
            }, 320)
        }
    }

    private fun openPanel() {
        if (!Perms.overlay(this)) {
            toast("Allow 'Display over other apps' for FrostByte first (open FrostByte > Settings)")
            return
        }
        val p = panel ?: PanelOverlay(this, panelCb).also { panel = it }
        if (p.isShowing) p.hide() else p.show()
    }

    /** AFK mode: screen stays awake and the phone is watched. It never sends input to the game. */
    private fun setAfk(on: Boolean, announce: Boolean) {
        afk = on
        afkActive = on
        if (on && Prefs.afkTouchLock(this) && !locked) {
            lockTouch()
            afkLocked = locked
        }
        if (!on && afkLocked) {
            unlockTouch()
            afkLocked = false
        }
        applyBoost()
        refreshNotification()
        if (announce) toast(if (on) "AFK mode on: screen stays awake and the phone is monitored" else "AFK mode off")
    }

    private fun lockTouch() {
        if (locked) return
        if (!Perms.overlay(this)) {
            toast("Touch Lock needs 'Display over other apps'")
            return
        }
        panel?.hide()
        val l = TouchLockOverlay(this) { unlockTouch() }
        if (l.show()) {
            lockOverlay = l
            locked = true
            refreshNotification()
        } else {
            toast("Couldn't start Touch Lock")
        }
    }

    private fun unlockTouch() {
        lockOverlay?.hide()
        lockOverlay = null
        locked = false
        refreshNotification()
    }

    // ------------------------------------------------------------------ monitoring (cheap, stops with gaming)

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                val b = DeviceStats.battery(this@GamingService)
                b.tempC?.let { t -> tempMax = maxOf(tempMax ?: t, t) }
                val th = b.thermal
                if (th != lastThermal) {
                    lastThermal = th
                    main.post {
                        if (running) {
                            refreshNotification()
                            if (th == ThermalLevel.HOT || th == ThermalLevel.CRITICAL) postHeatAlert(th)
                        }
                    }
                }
                if (afk && !b.charging) {
                    val pct = b.percent
                    if (pct != null && pct <= Prefs.afkStopBattery(this@GamingService)) {
                        main.post { if (running) { postAlert("AFK ended", "Battery reached $pct%. Gaming Mode stopped to protect your battery."); stopGaming() } }
                        return@launch
                    }
                }
                if (afk && Prefs.afkStopHot(this@GamingService) && th == ThermalLevel.CRITICAL) {
                    main.post { if (running) { postAlert("AFK ended", "The phone is critically hot. Gaming Mode stopped so it can cool down."); stopGaming() } }
                    return@launch
                }
                if (Perms.usage(this@GamingService)) {
                    pollForeground()
                    val now = System.currentTimeMillis()
                    if (currentFg == gamePkg) {
                        lastGameSeen = now
                    } else if (currentFg != packageName && now - startMs > 45_000 && now - lastGameSeen > 60_000) {
                        main.post { if (running) stopGaming() }   // game closed -> gaming mode ends, notification disappears
                        return@launch
                    }
                }
                val base = Prefs.profile(this@GamingService, gameId).monitorMs
                delay(if (afk && Prefs.afkLowPower(this@GamingService)) maxOf(base, 15_000L) else base)
            }
        }
    }

    private fun pollForeground() {
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val from = if (lastEventTime == 0L) now - 60_000 else lastEventTime - 1
            val events = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) currentFg = e.packageName
                if (e.timeStamp > lastEventTime) lastEventTime = e.timeStamp
            }
        } catch (_: SecurityException) {
            // Usage Access revoked while running
        }
    }

    // ------------------------------------------------------------------ notifications

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_MAIN, "Gaming Mode", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while you play, tap to open the FrostByte panel"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Thermal alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Warns when the phone gets hot while gaming"
            },
        )
    }

    private fun svc(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, GamingService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(): Notification {
        val open = svc(ACTION_OPEN_PANEL, 1)
        val text = if (afk) "AFK mode on \u00B7 screen stays awake \u00B7 tap for panel" else when (lastThermal) {
            ThermalLevel.HOT -> "Device is getting hot \u00B7 tap to open panel"
            ThermalLevel.CRITICAL -> "Critical heat \u00B7 consider stopping"
            else -> "Tap to open FrostByte Game Booster"
        }
        val b = NotificationCompat.Builder(this, CH_MAIN)
            .setSmallIcon(R.drawable.ic_stat_frost)
            .setContentTitle("\uD83C\uDFAE FrostByte Game Booster")
            .setContentText(text)
            .setSubText(gameName)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(afk)
            .setShowWhen(afk)
            .setWhen(startMs)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(0xFF38D0FF.toInt())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "OPEN PANEL", open)
        if (locked) b.addAction(0, "UNLOCK", svc(ACTION_UNLOCK, 3)) else b.addAction(0, "BOOST", svc(ACTION_BOOST, 2))
        b.addAction(0, "STOP", svc(ACTION_STOP, 4))
        return b.build()
    }

    private fun refreshNotification() {
        if (!running) return
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_MAIN, buildNotification())
    }

    private fun postHeatAlert(level: ThermalLevel) {
        val (title, body) = if (level == ThermalLevel.CRITICAL) {
            "Critical heat" to "Stop gaming and let the device cool down."
        } else {
            "Device is getting hot" to "Lower graphics/FPS or take a break. Performance may be reduced."
        }
        postAlert(title, body)
    }

    private fun postAlert(title: String, body: String) {
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_frost)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ALERT, n)
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }
}
