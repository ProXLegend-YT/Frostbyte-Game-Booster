package com.frostbyte.gamebooster.ui

import android.content.ContentResolver
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frostbyte.gamebooster.data.Games
import com.frostbyte.gamebooster.data.Prefs
import com.frostbyte.gamebooster.system.DeviceStats
import com.frostbyte.gamebooster.system.MemoryBoost
import com.frostbyte.gamebooster.system.Perms
import com.frostbyte.gamebooster.system.ThermalLevel
import com.frostbyte.gamebooster.ui.theme.Frost
import kotlinx.coroutines.launch

private data class CoachItem(
    val title: String,
    val detail: String,
    val ok: Boolean?,                    // true = good, false = problem, null = tip
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
)

/** Real checks of what is slowing games down on THIS phone right now, with one-tap fixes wherever Android allows. */
@Composable
fun PerformanceCoachScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resume = rememberResumeTick()
    var version by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var limitSet by remember { mutableStateOf(Prefs.devLimitSet(context)) }

    val items = remember(resume, version) {
        val cr = context.contentResolver
        val b = DeviceStats.battery(context)
        val m = DeviceStats.memory(context)
        val thr = DeviceStats.thermalStatus(context)
        val saver = DeviceStats.powerSave(context)
        val storage = DeviceStats.freeStoragePercent()
        val syncOn = try { ContentResolver.getMasterSyncAutomatically() } catch (_: SecurityException) { false }
        val bright = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255
        val devOn = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        val keepActivities = Settings.Global.getInt(cr, "always_finish_activities", 0) == 1
        val hot = thr >= 2 || b.thermal == ThermalLevel.HOT || b.thermal == ThermalLevel.CRITICAL
        val robloxInstalled = Games.detectInstalled(context).any { it.def.id == "roblox" }

        buildList {
            add(CoachItem(
                "Android Battery Saver",
                if (saver) "ON. It limits CPU speed, so games run slower. Turn it off while gaming." else "Off, good.",
                !saver, if (saver) "Open settings" else null,
            ) { Perms.openBatterySaver(context) })

            add(CoachItem(
                "Heat and throttling",
                "Battery ${b.tempC?.let { "%.0f\u00B0C".format(it) } ?: "temp unavailable"}" +
                    (b.thermal?.let { " (${it.label})" } ?: "") + ". Android throttle: ${DeviceStats.throttleLabel(thr)}." +
                    (if (hot) " A hot phone slows the game. Let it cool, remove the case, lower graphics." else ""),
                !hot,
            ))

            add(CoachItem(
                "Charging while gaming",
                if (b.charging) "Charging adds heat, which causes throttling. Unplug if the phone gets warm." else "Not charging, good.",
                !(b.charging && (b.tempC ?: 0f) >= 37f),
            ))

            add(CoachItem(
                "Free memory",
                "%.1f GB free, ${m.usedPercent}%% used.".format(m.availBytes / 1_073_741_824.0) +
                    (if (m.usedPercent >= 85) " Low memory makes games stutter." else ""),
                m.usedPercent < 85, "Free memory now",
            ) {
                if (!busy) {
                    busy = true
                    scope.launch {
                        note = MemoryBoost.sweep(context, emptySet()).summary()
                        busy = false
                        version++
                    }
                }
            })

            add(CoachItem(
                "Free storage",
                "$storage% of storage free." + (if (storage < 10) " Below about 10% games load slowly and stutter. Delete unused files or apps." else ""),
                storage >= 10, if (storage < 10) "Open storage" else null,
            ) { Perms.open(context, Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) })

            val syncOk = !syncOn || Prefs.pauseSync(context)
            add(CoachItem(
                "Background sync",
                if (syncOk) "Handled (off or paused while gaming)." else "Auto-sync is ON and keeps working in the background while you play.",
                syncOk, if (syncOk) null else "Pause while gaming",
            ) { Prefs.setPauseSync(context, true); version++ })

            val brightOk = bright <= 70 || Prefs.lowerBrightness(context)
            add(CoachItem(
                "Screen brightness",
                "Now about $bright%." + (if (!brightOk) " A bright screen adds heat. FrostByte can lower it while you play and restore it after." else ""),
                brightOk, if (brightOk) null else "Lower while gaming",
            ) {
                if (Perms.writeSettings(context)) { Prefs.setLowerBrightness(context, true); version++ } else Perms.openWriteSettings(context)
            })

            add(CoachItem("Display over other apps", "Needed for the in-game panel and Touch Lock.", Perms.overlay(context),
                if (Perms.overlay(context)) null else "Allow") { Perms.openOverlay(context) })
            add(CoachItem("Notifications", "Needed for the Game Booster notification.", Perms.notifications(context),
                if (Perms.notifications(context)) null else "Allow") { Perms.openNotificationSettings(context) })
            add(CoachItem("Do Not Disturb access", "Silences pop-ups and calls that interrupt play.", Perms.dnd(context),
                if (Perms.dnd(context)) null else "Allow") { Perms.openDnd(context) })
            add(CoachItem("Usage access", "Lets Gaming Mode end itself when the game closes and powers Clean & Play.", Perms.usage(context),
                if (Perms.usage(context)) null else "Allow") { Perms.openUsage(context) })

            add(CoachItem(
                "Developer options",
                if (devOn) "On." else "Off. To enable: Settings > About phone > Software information > tap Build number 7 times.",
                devOn, if (devOn) "Open Developer options" else null,
            ) { Perms.open(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) })

            if (devOn) {
                add(CoachItem(
                    "\"Don't keep activities\"",
                    if (keepActivities) "ON. It slows apps and games. Turn it off in Developer options." else "Off, good.",
                    !keepActivities, if (keepActivities) "Open Developer options" else null,
                ) { Perms.open(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) })
                add(CoachItem(
                    "Background process limit",
                    "The one developer option that really frees memory for games: Developer options > Apps > Background process limit > " +
                        "'At most 2 processes' while you play, then back to 'Standard limit'. Android only lets you set this by hand.",
                    if (limitSet) true else null, "Open Developer options",
                ) { Perms.open(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) })
            }

            if (robloxInstalled) {
                add(CoachItem(
                    "Roblox cache",
                    "If Roblox stutters after long use: App info > Storage > Clear cache. Your login and data stay.",
                    null, "Open Roblox info",
                ) { Perms.openAppInfo(context, "com.roblox.client") })
            }
        }
    }

    val problems = items.count { it.ok == false }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) { Text("\u2039 Back", color = Frost.Cyan) }
        Text("Performance Coach", color = Frost.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            if (problems == 0) "Nothing is holding your phone back right now." else "$problems thing(s) could be slowing your games.",
            color = if (problems == 0) Frost.Good else Frost.Warm, fontSize = 13.sp,
        )
        note?.let { Text(it, color = Frost.Good, fontSize = 12.sp) }

        items.forEach { item ->
            GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (item.ok) { true -> "\u2713"; false -> "\u26A0"; null -> "\u2139" },
                        color = when (item.ok) { true -> Frost.Good; false -> Frost.Warm; null -> Frost.Cyan },
                        fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = Frost.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(item.detail, color = Frost.Muted, fontSize = 11.sp)
                    }
                    if (item.actionLabel != null && item.action != null) {
                        TextButton(onClick = item.action) { Text(item.actionLabel, color = Frost.Cyan, fontSize = 12.sp) }
                    }
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("I set the background process limit", color = Frost.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("FrostByte will remind you to set it back when Gaming Mode ends.", color = Frost.Muted, fontSize = 11.sp)
                }
                Switch(
                    checked = limitSet,
                    onCheckedChange = { limitSet = it; Prefs.setDevLimitSet(context, it); version++ },
                    colors = SwitchDefaults.colors(checkedThumbColor = Frost.Bg, checkedTrackColor = Frost.Cyan),
                )
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Text(
                "Why can't FrostByte change developer options for you? Android only allows that with a special permission that has to be " +
                    "granted from a computer (ADB), and Android 10 has no phone-only way to do it. Everything above is something Android lets an app do or guide.",
                color = Frost.Muted, fontSize = 11.sp, modifier = Modifier.padding(12.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
