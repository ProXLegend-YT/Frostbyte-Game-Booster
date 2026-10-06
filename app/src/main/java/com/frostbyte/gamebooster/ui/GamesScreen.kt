package com.frostbyte.gamebooster.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frostbyte.gamebooster.data.GameProfile
import com.frostbyte.gamebooster.data.GameTips
import com.frostbyte.gamebooster.data.Games
import com.frostbyte.gamebooster.data.Prefs
import com.frostbyte.gamebooster.data.SessionStore
import com.frostbyte.gamebooster.system.Perms
import com.frostbyte.gamebooster.system.UsageHelper
import com.frostbyte.gamebooster.ui.theme.Frost
import com.frostbyte.gamebooster.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun GamesScreen() {
    val context = LocalContext.current
    val resume = rememberResumeTick()
    var version by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var boostFor by remember { mutableStateOf<String?>(null) }

    val games = remember(resume, version) { Games.detectInstalled(context) }
    val sessions = remember(resume, version) { SessionStore.all(context) }
    val usage by produceState(emptyMap<String, Long>(), games, resume) {
        value = withContext(Dispatchers.Default) { UsageHelper.lastPlayed(context, games.map { it.packageName }.toSet()) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Game Library", color = Frost.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Pick a profile for each game", color = Frost.Muted, fontSize = 13.sp)
            }
            OutlinedButton(onClick = { showAdd = true }) { Text("+ Add game", color = Frost.Cyan) }
        }
        Spacer(Modifier.height(8.dp))

        if (!Perms.usage(context)) {
            Text(
                "Tip: allow Usage access (Settings tab) to see 'last played' for every launch.",
                color = Frost.Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        if (games.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    "No supported games detected. Install Roblox, Minecraft Bedrock or PojavLauncher, or tap '+ Add game' for any other game.",
                    color = Frost.Muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(games, key = { it.packageName }) { g ->
                    val profile = Prefs.profile(context, g.def.id)
                    val lastSession = sessions.filter { it.gameId == g.def.id }.maxOfOrNull { it.endMs }
                    val last = listOfNotNull(usage[g.packageName], lastSession).maxOrNull()
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                GameIcon(g.icon, 52)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(g.label, color = Frost.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "Last played: " + (last?.let { Fmt.ago(System.currentTimeMillis(), it) } ?: "not yet"),
                                        color = Frost.Muted, fontSize = 12.sp,
                                    )
                                }
                                Button(
                                    onClick = { boostFor = g.packageName },
                                    shape = RoundedCornerShape(20.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Frost.Cyan, contentColor = Frost.Bg),
                                ) { Text("Launch", fontWeight = FontWeight.Bold) }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                GameProfile.entries.forEach { p ->
                                    FilterChip(
                                        selected = p == profile,
                                        onClick = { Prefs.setProfile(context, g.def.id, p); version++ },
                                        label = { Text(p.label, fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            labelColor = Frost.Text,
                                            selectedContainerColor = Frost.Cyan,
                                            selectedLabelColor = Frost.Bg,
                                        ),
                                    )
                                }
                            }
                            Text(GameTips.tip(g.def.id, profile), color = Frost.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                            if (g.isCustom) {
                                Text(
                                    "Remove from library", color = Frost.Critical, fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 6.dp).clickable { Prefs.removeCustomGame(context, g.packageName); version++ },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    boostFor?.let { pkg ->
        games.firstOrNull { it.packageName == pkg }?.let { g -> BoostDialog(g) { boostFor = null } }
    }

    if (showAdd) {
        val candidates by produceState<List<com.frostbyte.gamebooster.data.InstalledGame>?>(null) {
            value = withContext(Dispatchers.Default) { Games.launcherApps(context) }
        }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            containerColor = Frost.Card,
            titleContentColor = Frost.Text,
            title = { Text("Add a game") },
            text = {
                val list = candidates
                if (list == null) {
                    Text("Loading apps\u2026", color = Frost.Muted)
                } else {
                    LazyColumn(Modifier.height(320.dp)) {
                        items(list, key = { it.packageName }) { app ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    Prefs.addCustomGame(context, app.packageName)
                                    version++
                                    showAdd = false
                                    Toast.makeText(context, "${app.label} added", Toast.LENGTH_SHORT).show()
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                GameIcon(app.icon, 36)
                                Spacer(Modifier.width(10.dp))
                                Text(app.label, color = Frost.Text, fontSize = 14.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Close", color = Frost.Muted) } },
        )
    }
}
