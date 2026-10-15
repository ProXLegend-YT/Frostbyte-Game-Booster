package com.frostbyte.gamebooster.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.frostbyte.gamebooster.R
import com.frostbyte.gamebooster.data.Games
import com.frostbyte.gamebooster.data.SessionStore
import com.frostbyte.gamebooster.service.GamingService
import com.frostbyte.gamebooster.system.DeviceStats
import com.frostbyte.gamebooster.ui.theme.Frost
import com.frostbyte.gamebooster.util.Fmt
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    onOpenGames: () -> Unit,
    onOpenAfk: () -> Unit,
    onOpenManager: () -> Unit,
    onOpenClicker: () -> Unit,
    onOpenGhost: () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val resume = rememberResumeTick()

    var battery by remember { mutableStateOf(DeviceStats.battery(context)) }
    var memory by remember { mutableStateOf(DeviceStats.memory(context)) }
    var active by remember { mutableStateOf(GamingService.activeGameName) }
    var selected by remember { mutableIntStateOf(0) }
    var showBoost by remember { mutableStateOf(false) }

    val games = remember(resume) { Games.detectInstalled(context) }
    val sessions = remember(resume, active) { SessionStore.all(context) }
    val game = games.getOrNull(selected.coerceIn(0, (games.size - 1).coerceAtLeast(0)))

    // Refresh only while the screen is visible, every 5s (battery-friendly).
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                battery = DeviceStats.battery(context)
                memory = DeviceStats.memory(context)
                active = GamingService.activeGameName
                delay(5000)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_logo_small), null, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("FrostByte", color = Frost.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Game harder. Stay cooler.", color = Frost.Cyan, fontSize = 12.sp)
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                if (active != null) {
                    Text("\u25CF Gaming Mode active", color = Frost.Good, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(active.orEmpty(), color = Frost.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Pull down the notification shade and tap 'Game Booster' while playing.", color = Frost.Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { GamingService.stop(context) },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, Frost.Critical),
                    ) { Text("Stop Gaming Mode", color = Frost.Critical) }
                } else {
                    Text("Gaming Ready", color = Frost.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (game == null) "No supported games found yet." else "Selected: ${game.label}",
                        color = Frost.Muted, fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { if (game != null) showBoost = true else onOpenGames() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Frost.Cyan, contentColor = Frost.Bg),
                    ) { Text(if (game != null) "BOOST & PLAY" else "ADD A GAME", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                "Battery",
                battery.percent?.let { "$it%" } ?: "N/A",
                if (battery.charging) "Charging" else "On battery",
                Modifier.weight(1f),
            )
            StatTile(
                "Temperature",
                battery.tempC?.let { "%.0f\u00B0C".format(it) } ?: "N/A",
                battery.thermal?.label ?: "Unavailable",
                Modifier.weight(1f),
            )
            StatTile(
                "Memory",
                "%.1f GB".format(memory.availBytes / 1_073_741_824.0),
                "free \u00B7 ${memory.usedPercent}% used",
                Modifier.weight(1f),
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionTitle("Detected Games")
            Text("View all", color = Frost.Cyan, fontSize = 13.sp,
                modifier = Modifier.clickable { onOpenGames() }.padding(vertical = 8.dp))
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                if (games.isEmpty()) Text("Nothing detected", color = Frost.Muted, fontSize = 13.sp)
                games.forEachIndexed { i, g ->
                    Column(
                        Modifier.clickable { selected = i },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .border(2.dp, if (i == selected) Frost.Cyan else Color.Transparent, RoundedCornerShape(14.dp))
                                .padding(2.dp),
                        ) { GameIcon(g.icon, 52) }
                        Text(
                            g.label.substringBefore(' '),
                            color = if (i == selected) Frost.Cyan else Frost.Text,
                            fontSize = 11.sp,
                            fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        SectionTitle("Tools")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolCard("\uD83C\uDF19 AFK Mode", "Stay in game, longer", onOpenAfk, Modifier.weight(1f))
            ToolCard("\uD83E\uDDF9 App Manager", "Close apps before you play", onOpenManager, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolCard("\uD83D\uDC46 Auto Clicker", "Reconnect for you, 2 clickers", onOpenClicker, Modifier.weight(1f))
            ToolCard("\uD83D\uDC7B Ghost-Touch Test", "Check for phantom touches", onOpenGhost, Modifier.weight(1f))
        }

        SectionTitle("Gaming Statistics")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Total play time", Fmt.duration(sessions.sumOf { it.durationMs }), "all sessions", Modifier.weight(1f))
            StatTile("Sessions", sessions.size.toString(), "recorded locally", Modifier.weight(1f))
        }
    }

    if (showBoost && game != null) BoostDialog(game) { showBoost = false }
}
