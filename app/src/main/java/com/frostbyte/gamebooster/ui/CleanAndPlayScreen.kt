package com.frostbyte.gamebooster.ui

import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.frostbyte.gamebooster.data.InstalledGame
import com.frostbyte.gamebooster.data.Prefs
import com.frostbyte.gamebooster.service.GamingService
import com.frostbyte.gamebooster.system.MemoryBoost
import com.frostbyte.gamebooster.system.Perms
import com.frostbyte.gamebooster.system.RecentApp
import com.frostbyte.gamebooster.system.RecentApps
import com.frostbyte.gamebooster.ui.theme.Frost
import com.frostbyte.gamebooster.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Clean & Play: opens each recently used app's own App info page so you can tap Force stop (Android doesn't let
 * any normal app do that for you), then ends the remaining background processes and launches the game.
 */
@Composable
fun CleanAndPlayScreen(game: InstalledGame, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resume = rememberResumeTick()
    val hasUsage = remember(resume) { Perms.usage(context) }

    val apps by produceState(emptyList<RecentApp>(), resume) {
        value = withContext(Dispatchers.Default) { RecentApps.load(context, setOf(game.packageName)) }
    }
    var unchecked by remember { mutableStateOf(setOf<String>()) }
    var queue by remember { mutableStateOf(listOf<String>()) }
    var idx by remember { mutableIntStateOf(-1) }
    var lastTick by remember { mutableIntStateOf(resume) }
    var launching by remember { mutableStateOf(false) }

    fun go() {
        if (launching) return
        launching = true
        scope.launch {
            if (Prefs.sweepEnabled(context)) MemoryBoost.sweep(context, setOf(game.packageName))
            GamingService.start(context, game)
            if (!Games.launch(context, game)) {
                GamingService.stop(context)
                Toast.makeText(context, "Couldn't open ${game.label}", Toast.LENGTH_SHORT).show()
            }
            onBack()
        }
    }

    LaunchedEffect(resume) {
        if (idx >= 0 && resume != lastTick) {
            lastTick = resume
            val next = idx + 1
            if (next < queue.size) {
                idx = next
                Perms.openAppInfo(context, queue[next])
            } else {
                idx = -1
                go()
            }
        }
    }

    val selected = apps.filter { it.pkg !in unchecked }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack, enabled = !launching) { Text("\u2039 Back", color = Frost.Cyan) }
        Text("Clean & Play \u00B7 ${game.label}", color = Frost.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Force-stop apps, then your game launches automatically", color = Frost.Cyan, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Text(
                "Android restricts third-party apps from force-stopping other apps without special privileges, so you'll tap " +
                    "'Force stop' then OK on each app's own screen, then go back. FrostByte moves to the next app for you and " +
                    "launches ${game.label} at the end.",
                color = Frost.Muted, fontSize = 12.sp, modifier = Modifier.padding(14.dp),
            )
        }
        Spacer(Modifier.height(8.dp))

        when {
            launching -> Text("Boosting and launching ${game.label}\u2026", color = Frost.Good, fontSize = 14.sp)
            idx >= 0 -> GlassCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("App ${idx + 1} of ${queue.size}: tap Force stop, OK, then go back.", color = Frost.Text,
                        fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { idx = -1 }) { Text("Stop", color = Frost.Critical) }
                }
            }
            !hasUsage -> GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Usage access lets FrostByte list the apps you used recently.", color = Frost.Text, fontSize = 13.sp)
                    TextButton(onClick = { Perms.openUsage(context) }) { Text("Allow usage access", color = Frost.Cyan) }
                }
            }
            else -> Button(
                onClick = {
                    if (selected.isNotEmpty()) {
                        queue = selected.map { it.pkg }
                        lastTick = resume
                        idx = 0
                        Perms.openAppInfo(context, queue[0])
                    }
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Frost.Cyan, contentColor = Frost.Bg),
            ) { Text("Start clean-up (${selected.size} apps)", fontWeight = FontWeight.Bold) }
        }

        if (!launching && idx < 0) {
            OutlinedButton(onClick = { go() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Skip clean-up, boost & launch now", color = Frost.Cyan)
            }
            if (hasUsage && apps.isNotEmpty()) {
                LazyColumn(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(apps, key = { it.pkg }) { app ->
                        GlassCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = app.pkg !in unchecked,
                                    onCheckedChange = { on -> unchecked = if (on) unchecked - app.pkg else unchecked + app.pkg },
                                    colors = CheckboxDefaults.colors(checkedColor = Frost.Cyan, checkmarkColor = Frost.Bg, uncheckedColor = Frost.Muted),
                                )
                                GameIcon(app.icon, 36)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, color = Frost.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text("Used ${Fmt.ago(System.currentTimeMillis(), app.lastUsed)}", color = Frost.Muted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
