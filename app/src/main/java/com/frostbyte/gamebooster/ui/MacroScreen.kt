package com.frostbyte.gamebooster.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frostbyte.gamebooster.data.MacroStore
import com.frostbyte.gamebooster.data.Prefs
import com.frostbyte.gamebooster.service.ClickerService
import com.frostbyte.gamebooster.system.Perms
import com.frostbyte.gamebooster.ui.theme.Frost

/** "Tiny Task for your phone": record touches, replay them, loop them. The controls float over your game. */
@Composable
fun MacroScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resume = rememberResumeTick()
    var version by remember { mutableIntStateOf(0) }
    val a11y = remember(resume) { Perms.accessibility(context) }
    val barOn = remember(resume, version) { ClickerService.instance?.macro?.barVisible == true }
    val saved = remember(resume, version) { MacroStore.load(context) }
    val battOk = remember(resume) { Perms.batteryUnrestricted(context) }
    var gap by remember { mutableFloatStateOf(Prefs.macroGapSec(context).toFloat()) }
    var speed by remember { mutableFloatStateOf(Prefs.macroSpeed(context)) }
    var forward by remember { mutableStateOf(Prefs.macroForward(context)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(onClick = onBack) { Text("\u2039 Back", color = Frost.Cyan) }
        Text("Macro Recorder", color = Frost.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Tiny Task for your phone", color = Frost.Cyan, fontSize = 13.sp)

        GlassCard(Modifier.fillMaxWidth()) {
            Text(
                "Record your taps, holds and swipes once, then let FrostByte replay them with the same timing. Test it with " +
                    "Play, and when it works perfectly switch on Loop: it plays, waits a few seconds, and plays again, over and over. " +
                    "It only repeats touches you recorded and never reads your screen. Check the game's own rules before using automation.",
                color = Frost.Muted, fontSize = 12.sp, modifier = Modifier.padding(14.dp),
            )
        }

        if (!a11y) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Step 1: switch it on", color = Frost.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "The recorder uses the same Android accessibility service as the Auto Clicker. Open Accessibility settings, " +
                            "find Installed apps (or Downloaded apps), tap FrostByte Auto Clicker and turn it on.",
                        color = Frost.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp),
                    )
                    Button(
                        onClick = { Perms.openAccessibility(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = Frost.Cyan, contentColor = Frost.Bg),
                    ) { Text("Open Accessibility settings", fontWeight = FontWeight.Bold) }
                }
            }
        } else {
            Text("\u2713 Service is on", color = Frost.Good, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Step 2: show the recorder", color = Frost.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    "A small bar floats over your game: \u25CF record \u00B7 \u25B6 play once \u00B7 \uD83D\uDD01 loop \u00B7 " +
                        "\uD83D\uDEE1 ghost-touch shield \u00B7 \u2715 close \u00B7 \u2725 drag it anywhere. Tap \u25CF, do your touches in the game, " +
                        "then tap \u25A0 to stop. Turn the shield on AFTER you press Loop: while it is on, the bar is locked on purpose " +
                        "(hold the lock pill for 5 seconds to turn the shield off).",
                    color = Frost.Muted, fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val svc = ClickerService.instance
                            if (svc == null) Perms.openAccessibility(context) else { svc.macro.showBar(); version++ }
                        },
                        enabled = a11y,
                        colors = ButtonDefaults.buttonColors(containerColor = Frost.Cyan, contentColor = Frost.Bg),
                    ) { Text(if (barOn) "Refresh bar" else "Show recorder bar", fontWeight = FontWeight.Bold) }
                    if (barOn) {
                        OutlinedButton(onClick = { ClickerService.instance?.macro?.hideBar(); version++ }) {
                            Text("Hide", color = Frost.Critical)
                        }
                    }
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Your recording", color = Frost.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (saved == null) {
                    Text("Nothing recorded yet. Show the bar, tap \u25CF, and do your touches.", color = Frost.Muted, fontSize = 12.sp)
                } else {
                    val sec = saved.totalMs / 1000
                    Text(
                        "${saved.strokes.size} touches \u00B7 ${sec / 60}:${(sec % 60).toString().padStart(2, '0')} long \u00B7 " +
                            "recorded in ${if (saved.width > saved.height) "landscape" else "portrait"}",
                        color = Frost.Good, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                    OutlinedButton(onClick = { MacroStore.clear(context); version++ }) {
                        Text("Delete recording", color = Frost.Critical, fontSize = 12.sp)
                    }
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Loop settings", color = Frost.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (gap.toInt() == 0) "Loop again straight away" else "Wait ${gap.toInt()} s after each run, then play again",
                    color = Frost.Muted, fontSize = 12.sp,
                )
                Slider(
                    value = gap,
                    onValueChange = { gap = it },
                    onValueChangeFinished = { Prefs.setMacroGapSec(context, gap.toInt()) },
                    valueRange = 0f..60f,
                    steps = 59,
                    colors = SliderDefaults.colors(thumbColor = Frost.Cyan, activeTrackColor = Frost.Cyan, inactiveTrackColor = Frost.CardEdge),
                )
                Text("Playback speed", color = Frost.Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((v, label) in listOf(0.5f to "0.5\u00D7", 1f to "1\u00D7", 1.5f to "1.5\u00D7", 2f to "2\u00D7")) {
                        FilterChip(
                            selected = speed == v,
                            onClick = { speed = v; Prefs.setMacroSpeed(context, v) },
                            label = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(labelColor = Frost.Text, selectedContainerColor = Frost.Cyan, selectedLabelColor = Frost.Bg),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Let touches reach the game while recording", color = Frost.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Each touch is sent to the game right after you lift your finger, so menus move on while you record. " +
                                "While it is being sent the red tint disappears; wait for it to come back before your next touch.",
                            color = Frost.Muted, fontSize = 11.sp,
                        )
                    }
                    Switch(
                        checked = forward,
                        onCheckedChange = { forward = it; Prefs.setMacroForward(context, it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Frost.Bg, checkedTrackColor = Frost.Cyan),
                    )
                }
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Good to know", color = Frost.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    "\u2022 It records one finger: taps, long holds and swipes. Two-finger gestures aren't recorded.\n" +
                        "\u2022 Playback stops by itself if the screen turns off. While it plays, the screen is kept on.\n" +
                        "\u2022 Play it in the same screen orientation you recorded in.\n" +
                        "\u2022 The shield blocks ghost touches while it waits; it opens a tiny hole only for each recorded touch.\n" +
                        "\u2022 Playing a macro pauses the Auto Clicker (and the other way around).\n" +
                        "\u2022 If Android closes FrostByte while looping, the bar, shield and loop come back by themselves.",
                    color = Frost.Muted, fontSize = 12.sp,
                )
                if (!battOk) {
                    Text("Battery optimisation is ON: Android may stop long loops in the background.", color = Frost.Warm, fontSize = 12.sp)
                    Button(
                        onClick = { Perms.requestIgnoreBattery(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = Frost.Cyan, contentColor = Frost.Bg),
                    ) { Text("Allow running in background", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
