package com.frostbyte.gamebooster.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frostbyte.gamebooster.data.GameProfile
import com.frostbyte.gamebooster.data.Games
import com.frostbyte.gamebooster.data.InstalledGame
import com.frostbyte.gamebooster.data.Prefs
import com.frostbyte.gamebooster.service.GamingService
import com.frostbyte.gamebooster.system.DeviceStats
import com.frostbyte.gamebooster.system.MemoryBoost
import com.frostbyte.gamebooster.system.Perms
import com.frostbyte.gamebooster.system.ThermalLevel
import com.frostbyte.gamebooster.ui.theme.Frost
import kotlinx.coroutines.launch

/**
 * One-Tap Boost. Before the game opens it: ends other apps' background processes (measured), applies your gaming
 * profile, silences interruptions and starts Gaming Mode. For stubborn apps it can also walk you through Force stop.
 */
@Composable
fun BoostDialog(game: InstalledGame, onGuided: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile = remember { Prefs.profile(context, game.def.id) }
    val battery = remember { DeviceStats.battery(context) }
    val saver = remember { DeviceStats.powerSave(context) }
    var working by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        containerColor = Frost.Card,
        titleContentColor = Frost.Text,
        textContentColor = Frost.Muted,
        title = { Text("Boost & Play \u00B7 ${game.label}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Profile: ${profile.label}. ${profile.blurb}.", color = Frost.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Before launch FrostByte asks Android to end background processes of the apps you installed, once (then shows how much memory came back), " +
                        "applies your profile and silences interruptions. It can't raise CPU speed, add RAM or cool the phone.",
                    color = Frost.Muted, fontSize = 11.sp,
                )
                if (battery.thermal == ThermalLevel.HOT || battery.thermal == ThermalLevel.CRITICAL) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Your phone is already hot (${battery.tempC?.let { "%.0f\u00B0C".format(it) } ?: ""}). A hot phone throttles itself. Let it cool for the best performance.",
                        color = Frost.Hot, fontSize = 12.sp,
                    )
                }
                if (saver && profile != GameProfile.BATTERY) {
                    Spacer(Modifier.height(6.dp))
                    Text("Android Battery Saver is ON and limits CPU speed. Turn it off for best performance.", color = Frost.Warm, fontSize = 12.sp)
                    TextButton(onClick = { Perms.openBatterySaver(context) }) { Text("Open Battery Saver settings", color = Frost.Cyan, fontSize = 12.sp) }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { onGuided() }, enabled = !working) {
                    Text("Force-stop apps one by one first\u2026", color = Frost.Cyan, fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
                PermissionList()
            }
        },
        confirmButton = {
            Button(
                enabled = !working,
                onClick = {
                    working = true
                    scope.launch {
                        val note = if (Prefs.sweepEnabled(context)) MemoryBoost.sweep(context, setOf(game.packageName)).summary() else null
                        GamingService.start(context, game)
                        if (!Games.launch(context, game)) {
                            GamingService.stop(context)
                            Toast.makeText(context, "Couldn't open ${game.label}", Toast.LENGTH_SHORT).show()
                        } else if (note != null) {
                            Toast.makeText(context, note, Toast.LENGTH_LONG).show()
                        }
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Frost.Cyan, contentColor = Frost.Bg),
            ) { Text(if (working) "Boosting\u2026" else "Boost & launch ${game.label.substringBefore(' ')}") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel", color = Frost.Muted) } },
    )
}
