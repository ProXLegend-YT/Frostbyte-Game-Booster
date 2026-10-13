package com.frostbyte.gamebooster.ui

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
import com.frostbyte.gamebooster.data.ClickTarget
import com.frostbyte.gamebooster.data.ClickerStore
import com.frostbyte.gamebooster.service.ClickerService
import com.frostbyte.gamebooster.system.Perms
import com.frostbyte.gamebooster.ui.theme.Frost

@Composable
fun AutoClickerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resume = rememberResumeTick()
    var version by remember { mutableIntStateOf(0) }
    val a11y = remember(resume) { Perms.accessibility(context) }
    val controlsOn = remember(resume, version) { ClickerService.controlsVisible() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextButton(onClick = onBack) { Text("\u2039 Back", color = Frost.Cyan) }
        Text("Auto Clicker", color = Frost.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Tap the Reconnect button for you", color = Frost.Cyan, fontSize = 13.sp)

        GlassCard(Modifier.fillMaxWidth()) {
            Text(
                "Two clickers you place anywhere on screen. Each one either repeats every N seconds, or taps N seconds after your " +
                    "internet comes back (perfect for Roblox's Reconnect button). It only taps the points you set and cannot read your screen. " +
                    "Check the game's own rules before using automation.",
                color = Frost.Muted, fontSize = 12.sp, modifier = Modifier.padding(14.dp),
            )
        }

        if (!a11y) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Step 1: switch it on", color = Frost.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Android only lets apps tap the screen through an accessibility service. Open Accessibility settings, " +
                            "find Installed apps (or Downloaded apps), tap FrostByte Auto Clicker and turn it on. " +
                            "If the switch is greyed out on a newer Android: App info > menu (\u22EE) > Allow restricted settings.",
                        color = Frost.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp),
                    )
                    Button(
                        onClick = { Perms.openAccessibility(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = Frost.Cyan, contentColor = Frost.Bg),
                    ) { Text("Open Accessibility settings", fontWeight = FontWeight.Bold) }
                }
            }
        } else {
            Text("\u2713 Auto Clicker service is on", color = Frost.Good, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }

        for (i in 0 until ClickerStore.COUNT) {
            ClickerCard(i, version) {
                version++
                ClickerService.instance?.refreshMarkers()
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Step 2: place and run", color = Frost.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Tap Show controls, open your game, drag the numbered circles onto the buttons you want tapped, then press \u25B6. " +
                        "\u270E edit positions \u00B7 \uD83C\uDF11 black screen-off mode (clickers keep working, hold the faint pill to wake) \u00B7 " +
                        "\uD83D\uDEE1 clear touch shield that blocks ghost touches \u00B7 \u2715 close.",
                    color = Frost.Muted, fontSize = 12.sp,
                )
                if (a11y) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { ClickerService.instance?.showControls(); version++ },
                            colors = ButtonDefaults.buttonColors(containerColor = Frost.Cyan, contentColor = Frost.Bg),
                        ) { Text(if (controlsOn) "Refresh controls" else "Show controls", fontWeight = FontWeight.Bold) }
                        if (controlsOn) {
                            OutlinedButton(onClick = { ClickerService.instance?.hideAll(); version++ }) {
                                Text("Hide", color = Frost.Critical)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { ClickerService.instance?.testTap(0) }) { Text("Test 1 (3s)", color = Frost.Cyan, fontSize = 12.sp) }
                        OutlinedButton(onClick = { ClickerService.instance?.testTap(1) }) { Text("Test 2 (3s)", color = Frost.Cyan, fontSize = 12.sp) }
                    }
                } else {
                    Text("Switch the service on first (Step 1).", color = Frost.Warm, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ClickerCard(index: Int, version: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    var t by remember(version) { mutableStateOf(ClickerStore.get(context, index)) }
    var secs by remember(t.mode, version) { mutableFloatStateOf(t.seconds.toFloat()) }
    val netMode = t.mode == ClickTarget.MODE_NETWORK
    val range = if (netMode) 1f..60f else 5f..600f

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Clicker ${index + 1}", color = Frost.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (t.placed) "Position: X ${t.x} \u00B7 Y ${t.y}" else "Not placed yet (use Show controls)",
                        color = Frost.Muted, fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = t.enabled,
                    onCheckedChange = { ClickerStore.setEnabled(context, index, it); t = ClickerStore.get(context, index); onChanged() },
                    colors = SwitchDefaults.colors(checkedThumbColor = Frost.Bg, checkedTrackColor = Frost.Cyan),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = netMode,
                    onClick = { ClickerStore.setMode(context, index, ClickTarget.MODE_NETWORK); t = ClickerStore.get(context, index) },
                    label = { Text("After internet returns", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(labelColor = Frost.Text, selectedContainerColor = Frost.Cyan, selectedLabelColor = Frost.Bg),
                )
                FilterChip(
                    selected = !netMode,
                    onClick = { ClickerStore.setMode(context, index, ClickTarget.MODE_REPEAT); t = ClickerStore.get(context, index) },
                    label = { Text("Repeat", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(labelColor = Frost.Text, selectedContainerColor = Frost.Cyan, selectedLabelColor = Frost.Bg),
                )
            }
            Text(
                if (netMode) "Taps ${secs.toInt()} s after Wi-Fi/data comes back" else "Taps every ${secs.toInt()} s",
                color = Frost.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )
            Slider(
                value = secs.coerceIn(range.start, range.endInclusive),
                onValueChange = { secs = it },
                onValueChangeFinished = {
                    ClickerStore.setSeconds(context, index, t.mode, secs.toInt())
                    t = ClickerStore.get(context, index)
                },
                valueRange = range,
                colors = SliderDefaults.colors(thumbColor = Frost.Cyan, activeTrackColor = Frost.Cyan, inactiveTrackColor = Frost.CardEdge),
            )
        }
    }
}
