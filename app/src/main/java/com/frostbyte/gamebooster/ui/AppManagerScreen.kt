package com.frostbyte.gamebooster.ui

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
import androidx.compose.runtime.mutableLongStateOf
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
import com.frostbyte.gamebooster.data.Games
import com.frostbyte.gamebooster.system.DeviceStats
import com.frostbyte.gamebooster.system.Perms
import com.frostbyte.gamebooster.system.RecentApps
import com.frostbyte.gamebooster.ui.theme.Frost
import com.frostbyte.gamebooster.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resume = rememberResumeTick()
    val hasUsage = remember(resume) { Perms.usage(context) }

    val apps by produceState(emptyList<com.frostbyte.gamebooster.system.RecentApp>(), resume) {
        value = withContext(Dispatchers.Default) {
            val skip = Games.detectInstalled(context).map { it.packageName }.toSet()
            RecentApps.load(context, skip)
        }
    }
    var unchecked by remember { mutableStateOf(setOf<String>()) }

    // guided clean-up: opens each app's own App info page (where Android's Force stop button lives)
    var queue by remember { mutableStateOf(listOf<String>()) }
    var idx by remember { mutableIntStateOf(-1) }
    var lastTick by remember { mutableIntStateOf(resume) }
    var freeBefore by remember { mutableLongStateOf(0L) }
    var result by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(resume) {
        if (idx >= 0 && resume != lastTick) {
            lastTick = resume
            val next = idx + 1
            if (next < queue.size) {
                idx = next
                Perms.openAppInfo(context, queue[next])
            } else {
                idx = -1
                val after = DeviceStats.memory(context).availBytes
                val diffMb = (after - freeBefore) / 1_048_576
                result = if (diffMb > 0) "Free memory went up by about $diffMb MB (measured)." else "No measurable change in free memory (Android may have already reclaimed it)."
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("\u2039 Back", color = Frost.Cyan) }
        Text("Background App Manager", color = Frost.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Close apps before you play", color = Frost.Cyan, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))

        GlassCard(Modifier.fillMaxWidth()) {
            Text(
                "Android restricts third-party apps from force-stopping other apps without special privileges. " +
                    "So FrostByte shows apps you used recently and takes you to each app's own Force stop screen, one by one.",
                color = Frost.Muted, fontSize = 12.sp, modifier = Modifier.padding(14.dp),
            )
        }
        Spacer(Modifier.height(8.dp))

        if (!hasUsage) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Usage access is needed to list recently used apps.", color = Frost.Text, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { Perms.openUsage(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = Frost.Cyan, contentColor = Frost.Bg),
                    ) { Text("Allow usage access", fontWeight = FontWeight.Bold) }
                }
            }
        } else {
            val selected = apps.filter { it.pkg !in unchecked }
            if (idx >= 0) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Cleaning ${idx + 1} of ${queue.size}. Tap Force stop, then OK, then go back.", color = Frost.Text,
                            fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { idx = -1 }) { Text("Cancel", color = Frost.Critical) }
                    }
                }
            } else {
                Button(
                    onClick = {
                        if (selected.isNotEmpty()) {
                            queue = selected.map { it.pkg }
                            freeBefore = DeviceStats.memory(context).availBytes
                            result = null
                            lastTick = resume
                            idx = 0
                            Perms.openAppInfo(context, queue[0])
                        }
                    },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Frost.Cyan, contentColor = Frost.Bg),
                ) { Text("Guided clean-up (${selected.size} apps)", fontWeight = FontWeight.Bold) }
            }
            result?.let { Text(it, color = Frost.Good, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }

            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Perms.open(context, Intent(Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS)) }) {
                    Text("All apps", color = Frost.Cyan, fontSize = 12.sp)
                }
                OutlinedButton(onClick = { Perms.open(context, Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }) {
                    Text("Battery usage", color = Frost.Cyan, fontSize = 12.sp)
                }
            }

            if (apps.isEmpty()) {
                Text("No recently used apps found.", color = Frost.Muted, fontSize = 13.sp)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                    Text(
                                        "Used ${Fmt.ago(System.currentTimeMillis(), app.lastUsed)} \u00B7 ${Fmt.duration(app.foregroundMs)} today",
                                        color = Frost.Muted, fontSize = 11.sp,
                                    )
                                }
                                TextButton(onClick = { Perms.openAppInfo(context, app.pkg) }) { Text("Force stop\u2026", color = Frost.Cyan, fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}
