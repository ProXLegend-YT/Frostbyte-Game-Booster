package com.frostbyte.gamebooster.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frostbyte.gamebooster.data.NO_TEMP
import com.frostbyte.gamebooster.data.Session
import com.frostbyte.gamebooster.data.SessionStore
import com.frostbyte.gamebooster.ui.theme.Frost
import com.frostbyte.gamebooster.util.Fmt
import java.text.DateFormat
import java.util.Date

private fun temp(t: Float) = if (t == NO_TEMP) "-" else "%.0f\u00B0C".format(t)
private fun bat(b: Int) = if (b < 0) "-" else "$b%"

@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val resume = rememberResumeTick()
    val sessions = remember(version, resume) { SessionStore.all(context).sortedByDescending { it.startMs } }
    val byGame = remember(sessions) {
        sessions.groupBy { it.gameName }
            .map { (name, list) -> Triple(name, list.sumOf { it.durationMs }, list.size) }
            .sortedByDescending { it.second }
    }
    val timeFmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Gaming History", color = Frost.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Stored only on this phone", color = Frost.Muted, fontSize = 12.sp)
            }
            if (sessions.isNotEmpty()) {
                OutlinedButton(onClick = { SessionStore.clear(context); version++ }) { Text("Clear", color = Frost.Critical) }
            }
        }

        if (sessions.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(
                    "No sessions yet. Use BOOST & PLAY, play for a bit, and your sessions (time, battery, temperature) will show up here.",
                    color = Frost.Muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            LazyColumn(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("Total time", Fmt.duration(sessions.sumOf { it.durationMs }), "all games", Modifier.weight(1f))
                        StatTile("Sessions", sessions.size.toString(), "recorded", Modifier.weight(1f))
                    }
                }
                item { SectionTitle("By game") }
                items(byGame, key = { it.first }) { (name, ms, count) ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = Frost.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(Fmt.duration(ms), color = Frost.Cyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text("$count session(s)", color = Frost.Muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
                item { SectionTitle("Recent sessions") }
                items(sessions.take(30), key = { it.startMs }) { s -> SessionRow(s, timeFmt.format(Date(s.startMs))) }
            }
        }
    }
}

@Composable
private fun SessionRow(s: Session, whenText: String) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(s.gameName, color = Frost.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(Fmt.duration(s.durationMs), color = Frost.Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Text(whenText, color = Frost.Muted, fontSize = 11.sp)
            Text("Battery ${bat(s.batStart)} \u2192 ${bat(s.batEnd)}  \u00B7  Temp ${temp(s.tempStart)} \u2192 ${temp(s.tempEnd)} (peak ${temp(s.tempMax)})",
                color = Frost.Muted, fontSize = 11.sp)
        }
    }
}
