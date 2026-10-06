package com.frostbyte.gamebooster.ui

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.frostbyte.gamebooster.data.Games
import com.frostbyte.gamebooster.data.Prefs
import com.frostbyte.gamebooster.service.GamingService
import com.frostbyte.gamebooster.system.DeviceStats
import com.frostbyte.gamebooster.ui.theme.Frost
import com.frostbyte.gamebooster.util.Fmt
import kotlinx.coroutines.delay

@Composable
fun AfkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val resume = rememberResumeTick()
    val games = remember(resume) { Games.detectInstalled(context) }
    var selected by remember { mutableIntStateOf(0) }

    var lock by remember { mutableStateOf(Prefs.afkTouchLock(context)) }
    var lowPower by remember { mutableStateOf(Prefs.afkLowPower(context)) }
    var stopHot by remember { mutableStateOf(Prefs.afkStopHot(context)) }
    var stopBatt by remember { mutableFloatStateOf(Prefs.afkStopBattery(context).toFloat()) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var battery by remember { mutableStateOf(DeviceStats.battery(context)) }
    var active by remember { mutableStateOf(GamingService.afkActive) }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                battery = DeviceStats.battery(context)
                active = GamingService.afkActive
                delay(1000)
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("\u2039 Back", color = Frost.Cyan) }
        }
        Text("AFK Gaming Mode", color = Frost.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Stay in game, longer", color = Frost.Cyan, fontSize = 13.sp)

        GlassCard(Modifier.fillMaxWidth()) {
            Text(
                "AFK Mode only keeps your screen awake and watches battery, temperature and network. It never taps, moves, clicks or plays for you, and it doesn't get around any game's own idle or AFK rules.",
                color = Frost.Muted, fontSize = 12.sp, modifier = Modifier.padding(14.dp),
            )
        }

        if (active) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("\u25CF AFK mode is ON", color = Frost.Good, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(Fmt.duration(now - GamingService.activeStartMs), color = Frost.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Battery ${battery.percent?.let { "$it%" } ?: "N/A"}  \u00B7  ${battery.tempC?.let { "%.0f\u00B0C".format(it) } ?: "temp N/A"}" +
                            (battery.thermal?.let { "  \u00B7  ${it.label}" } ?: ""),
                        color = Frost.Muted, fontSize = 13.sp,
                    )
                }
            }
            Button(
                onClick = { GamingService.toggleAfk(context) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Frost.Critical, contentColor = Frost.Text),
            ) { Text("Turn AFK mode off", fontWeight = FontWeight.Bold) }
        } else if (GamingService.activeGameName != null) {
            Button(
                onClick = { GamingService.toggleAfk(context) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Frost.Cyan, contentColor = Frost.Bg),
            ) { Text("Turn AFK mode on for ${GamingService.activeGameName}", fontWeight = FontWeight.Bold) }
        } else {
            if (games.isEmpty()) {
                Text("Add a game in the Games tab first.", color = Frost.Muted, fontSize = 13.sp)
            } else {
                Text("Game", color = Frost.Muted, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    games.forEachIndexed { i, g ->
                        FilterChip(
                            selected = i == selected,
                            onClick = { selected = i },
                            label = { Text(g.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = Frost.Text, selectedContainerColor = Frost.Cyan, selectedLabelColor = Frost.Bg,
                            ),
                        )
                    }
                }
                Button(
                    onClick = {
                        val g = games.getOrNull(selected)
                        if (g != null) {
                            GamingService.start(context, g, afk = true)
                            if (!Games.launch(context, g)) {
                                GamingService.stop(context)
                                Toast.makeText(context, "Couldn't open ${g.label}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Frost.Cyan, contentColor = Frost.Bg),
                ) { Text("START AFK & LAUNCH", fontWeight = FontWeight.Bold) }
            }
        }

        SectionTitle("AFK options")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                AfkSwitch("Touch Lock on start", "Covers the screen so pockets and pets can't tap. Hold the pill 1.2s to unlock.", lock) {
                    lock = it; Prefs.setAfkTouchLock(context, it)
                }
                AfkSwitch("Low-power monitoring", "Checks the phone every 15s instead of 5-10s.", lowPower) {
                    lowPower = it; Prefs.setAfkLowPower(context, it)
                }
                AfkSwitch("Stop on critical heat", "Ends Gaming Mode if the phone reaches a critical temperature.", stopHot) {
                    stopHot = it; Prefs.setAfkStopHot(context, it)
                }
                Text("Stop when battery reaches ${stopBatt.toInt()}%", color = Frost.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp))
                Slider(
                    value = stopBatt, onValueChange = { stopBatt = it },
                    onValueChangeFinished = { Prefs.setAfkStopBattery(context, stopBatt.toInt()) },
                    valueRange = 5f..40f, steps = 6,
                    colors = SliderDefaults.colors(thumbColor = Frost.Cyan, activeTrackColor = Frost.Cyan, inactiveTrackColor = Frost.CardEdge),
                )
                Text("Not applied while charging. Screen-awake needs 'Display over other apps'.", color = Frost.Muted, fontSize = 11.sp)
            }
        }

        SectionTitle("Permissions")
        GlassCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { PermissionList() } }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AfkSwitch(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = Frost.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Frost.Muted, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Frost.Bg, checkedTrackColor = Frost.Cyan))
    }
}
