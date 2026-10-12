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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.frostbyte.gamebooster.data.Prefs
import com.frostbyte.gamebooster.system.DeviceStats
import com.frostbyte.gamebooster.system.NetworkProbe
import com.frostbyte.gamebooster.system.ThermalLevel
import com.frostbyte.gamebooster.ui.theme.Frost
import kotlinx.coroutines.delay

private fun levelColor(t: ThermalLevel?): Color = when (t) {
    null -> Frost.Muted
    ThermalLevel.COOL -> Frost.Good
    ThermalLevel.WARM -> Frost.Warm
    ThermalLevel.HOT -> Frost.Hot
    ThermalLevel.CRITICAL -> Frost.Critical
}

@Composable
fun PerformanceScreen(onOpenCoach: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var battery by remember { mutableStateOf(DeviceStats.battery(context)) }
    var memory by remember { mutableStateOf(DeviceStats.memory(context)) }
    var saver by remember { mutableStateOf(DeviceStats.powerSave(context)) }
    var net by remember { mutableStateOf(NetworkProbe.label(context)) }
    var thr by remember { mutableIntStateOf(DeviceStats.thermalStatus(context)) }
    var headroom by remember { mutableStateOf(DeviceStats.thermalHeadroom(context)) }
    var pings by remember { mutableStateOf(listOf<Int?>()) }

    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                battery = DeviceStats.battery(context)
                memory = DeviceStats.memory(context)
                saver = DeviceStats.powerSave(context)
                net = NetworkProbe.label(context)
                thr = DeviceStats.thermalStatus(context)
                headroom = DeviceStats.thermalHeadroom(context)
                delay(3000)
            }
        }
    }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (Prefs.pingEnabled(context)) {
                while (true) {
                    pings = (pings + NetworkProbe.pingMs()).takeLast(5)
                    delay(4000)
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Performance", color = Frost.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Live readings straight from Android. Nothing here is estimated or invented.", color = Frost.Muted, fontSize = 12.sp)
        ToolCard("\uD83D\uDE80 Performance Coach", "Finds what is slowing your games, with one-tap fixes", onOpenCoach, Modifier.fillMaxWidth())

        val th = battery.thermal
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Thermal Guard", color = Frost.Muted, fontSize = 12.sp)
                Text(battery.tempC?.let { "%.1f\u00B0C".format(it) } ?: "Temperature information unavailable",
                    color = Frost.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(th?.label ?: "", color = levelColor(th), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Android throttle: ${DeviceStats.throttleLabel(thr)}" +
                        (headroom?.let { "  \u00B7  thermal headroom ${(it * 100).toInt()}%" } ?: ""),
                    color = if (thr >= 2) Frost.Hot else Frost.Muted, fontSize = 12.sp,
                )                Text(
                    when (th) {
                        ThermalLevel.WARM -> "Consider reducing graphics."
                        ThermalLevel.HOT -> "Device is getting hot. Performance may be reduced."
                        ThermalLevel.CRITICAL -> "Stop gaming and allow the device to cool."
                        else -> "Battery temperature. FrostByte can warn you, it can't physically cool the phone."
                    },
                    color = Frost.Muted, fontSize = 11.sp,
                )
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Memory Monitor", color = Frost.Muted, fontSize = 12.sp)
                Text("%.2f GB free of %.2f GB".format(memory.availBytes / 1_073_741_824.0, memory.totalBytes / 1_073_741_824.0),
                    color = Frost.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { memory.usedPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (memory.usedPercent >= 90) Frost.Hot else Frost.Cyan,
                    trackColor = Frost.CardEdge,
                )
                Text("${memory.usedPercent}% used. FrostByte can't add RAM; closing apps yourself frees it.", color = Frost.Muted, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Battery", color = Frost.Muted, fontSize = 12.sp)
                Text(battery.percent?.let { "$it%" } ?: "N/A", color = Frost.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(
                    progress = { (battery.percent ?: 0) / 100f },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    color = Frost.Good,
                    trackColor = Frost.CardEdge,
                )
                Text(
                    (if (battery.charging) "Charging" else "On battery") + " \u00B7 Battery Saver " + (if (saver) "on" else "off"),
                    color = Frost.Muted, fontSize = 12.sp,
                )
                Text("Remaining-time estimates appear in the in-game panel once enough real drain is measured.", color = Frost.Muted, fontSize = 11.sp)
            }
        }

        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Network Monitor", color = Frost.Muted, fontSize = 12.sp)
                Text(net, color = Frost.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        !Prefs.pingEnabled(context) -> "Ping check is off (Settings)"
                        pings.isEmpty() -> "Measuring ping\u2026"
                        pings.last() == null -> "Ping unavailable"
                        else -> "Ping ${pings.last()} ms \u00B7 ${NetworkProbe.quality(pings)}"
                    },
                    color = Frost.Cyan, fontSize = 13.sp,
                )
                Text("Ping = real connection time to 1.1.1.1, only measured while this screen is open.", color = Frost.Muted, fontSize = 11.sp)
            }
        }
    }
}
