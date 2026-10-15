package com.frostbyte.gamebooster.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frostbyte.gamebooster.data.PanelPresets
import com.frostbyte.gamebooster.data.Prefs
import com.frostbyte.gamebooster.ui.theme.Frost

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = Frost.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Frost.Muted, fontSize = 11.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Frost.Bg, checkedTrackColor = Frost.Cyan),
        )
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var opacity by remember { mutableFloatStateOf(Prefs.opacity(context)) }
    var reduced by remember { mutableStateOf(Prefs.reducedMotion(context)) }
    var ping by remember { mutableStateOf(Prefs.pingEnabled(context)) }
    var custom by remember { mutableStateOf(Prefs.custom(context)) }
    var preset by remember { mutableStateOf(Prefs.preset(context)) }
    var cards by remember { mutableStateOf(Prefs.panelCards(context)) }
    var shortcuts by remember { mutableStateOf(Prefs.shortcuts(context)) }
    var amoled by remember { mutableStateOf(Prefs.amoled(context)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", color = Frost.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)

        SectionTitle("Permissions")
        GlassCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { PermissionList() } }

        SectionTitle("In-game panel")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Panel opacity: ${(opacity * 100).toInt()}%", color = Frost.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    onValueChangeFinished = { Prefs.setOpacity(context, opacity) },
                    valueRange = 0.4f..0.95f,
                    colors = SliderDefaults.colors(thumbColor = Frost.Cyan, activeTrackColor = Frost.Cyan, inactiveTrackColor = Frost.CardEdge),
                )
                Text("Lower = see more of your game behind the panel.", color = Frost.Muted, fontSize = 11.sp)
                SwitchRow("Reduced motion", "Panel appears instantly, no slide animation.", reduced) {
                    reduced = it; Prefs.setReducedMotion(context, it)
                }
                SwitchRow("Ping check", "Measures connection time to 1.1.1.1 while the panel/Performance screen is open.", ping) {
                    ping = it; Prefs.setPingEnabled(context, it)
                }
            }
        }

        SectionTitle("Panel style")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PanelPresets.all.forEach { p ->
                        FilterChip(
                            selected = preset == p.id,
                            onClick = {
                                PanelPresets.apply(context, p)
                                preset = p.id; opacity = p.opacity; cards = p.cards; shortcuts = p.shortcuts
                            },
                            label = { Text(p.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = Frost.Text, selectedContainerColor = Frost.Cyan, selectedLabelColor = Frost.Bg,
                            ),
                        )
                    }
                }
                Text("Presets set colours, opacity, cards and shortcuts. Changes show the next time you open the panel.", color = Frost.Muted, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }

        SectionTitle("Panel cards")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                listOf("perf" to "Performance", "thermal" to "Thermal Guard", "mem" to "Memory", "batt" to "Battery",
                    "net" to "Network", "block" to "Block during game").forEach { (id, label) ->
                    SwitchRow(label, "", id in cards) { on ->
                        cards = if (on) cards + id else cards - id
                        Prefs.setPanelCards(context, cards); Prefs.setPreset(context, "custom"); preset = "custom"
                    }
                }
            }
        }

        SectionTitle("Panel shortcuts")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                listOf("lock" to "Touch Lock", "shot" to "Screenshot", "rec" to "Screen record", "afk" to "AFK mode",
                    "vol" to "Volume", "net" to "Network settings", "settings" to "Settings").forEach { (id, label) ->
                    SwitchRow(label, "", id in shortcuts) { on ->
                        shortcuts = if (on) shortcuts + id else shortcuts - id
                        Prefs.setShortcuts(context, shortcuts); Prefs.setPreset(context, "custom"); preset = "custom"
                    }
                }
            }
        }

        SectionTitle("Appearance")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                SwitchRow("AMOLED black theme", "True black app background, great on OLED screens.", amoled) { on ->
                    amoled = on
                    Prefs.setAmoled(context, on)
                    (context as? android.app.Activity)?.recreate()
                }
            }
        }

        SectionTitle("Custom profile: Block during game")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                SwitchRow("Block notifications", "Do Not Disturb (priority only) while gaming.", custom.blockNotifications) {
                    custom = custom.copy(blockNotifications = it); Prefs.setCustom(context, custom)
                }
                SwitchRow("Block calls", "Uses 'Alarms only' so game sound keeps playing. Also silences notifications.", custom.blockCalls) {
                    custom = custom.copy(blockCalls = it); Prefs.setCustom(context, custom)
                }
                SwitchRow("Prevent screen timeout", "Screen stays on while Gaming Mode runs.", custom.keepAwake) {
                    custom = custom.copy(keepAwake = it); Prefs.setCustom(context, custom)
                }
                Text("Used when a game's profile is set to Custom (Games tab or the in-game panel).", color = Frost.Muted, fontSize = 11.sp)
            }
        }

        SectionTitle("Honest limits")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("\u2022 FrostByte can't raise CPU speed, add RAM, boost FPS or physically cool your phone.", color = Frost.Muted, fontSize = 12.sp)
                Text("\u2022 Android restricts third-party apps from force-stopping other apps without special privileges. The Background App Manager lists recently used apps and walks you through each app's own Force Stop screen.", color = Frost.Muted, fontSize = 12.sp)
                Text("\u2022 Touch Lock covers the screen with an invisible layer; Android has no switch to disable the touchscreen. Hold the pill 5s, or tap UNLOCK in the notification.", color = Frost.Muted, fontSize = 12.sp)
                Text("\u2022 Screenshots and recordings always show Android's own capture prompt first, and a notification stays visible while capturing. Recordings are video-only for now. Files go to Pictures/FrostByte and Movies/FrostByte.", color = Frost.Muted, fontSize = 12.sp)
                Text("\u2022 Everything stays on this phone. No account, no cloud.", color = Frost.Muted, fontSize = 12.sp)
            }
        }
    }
}
