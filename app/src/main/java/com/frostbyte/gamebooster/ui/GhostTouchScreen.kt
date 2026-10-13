package com.frostbyte.gamebooster.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frostbyte.gamebooster.ui.theme.Frost

/** Shows every touch the screen registers. Hands off for 30 seconds: any dots that appear are ghost touches. */
@Composable
fun GhostTouchScreen(onBack: () -> Unit) {
    var dots by remember { mutableStateOf(listOf<Offset>()) }
    var count by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("\u2039 Back", color = Frost.Cyan) }
        Text("Ghost-Touch Test", color = Frost.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Put the phone down and don't touch it for 30 seconds. Every dot below is a touch the screen registered. " +
                "Dots appearing by themselves = ghost touches (a hardware or charger problem, not an app).",
            color = Frost.Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Touches registered: $count", color = if (count == 0) Frost.Good else Frost.Warm, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { dots = emptyList(); count = 0 }) { Text("Reset", color = Frost.Cyan) }
        }
        Box(
            Modifier.fillMaxWidth().height(260.dp).padding(vertical = 8.dp).clip(RoundedCornerShape(16.dp))
                .background(Frost.Card)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent()
                            ev.changes.forEach { ch ->
                                if (ch.pressed) {
                                    dots = (dots + ch.position).takeLast(60)
                                    if (!ch.previousPressed) count++
                                }
                            }
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                dots.forEach { drawCircle(Frost.Cyan, 14f, it) }
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("If ghost touches show up, try in this order:", color = Frost.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            listOf(
                "Unplug the charger and test again. A bad or cheap charger is a very common cause of ghost touches.",
                "Remove the screen protector and case, wipe the screen dry.",
                "Samsung: Settings > Display > Touch sensitivity: turn it OFF (it can cause phantom touches).",
                "Restart the phone. Then test in Safe mode (hold power > hold Power off > Safe mode). If it's fine there, an app is the cause.",
                "Samsung: dial *#0*# and open Touch to run the built-in hardware test. A fault there means a screen/board repair.",
                "In games use the \uD83D\uDEE1 touch shield / Touch Lock in FrostByte so stray touches can't reach the game.",
            ).forEach { Text("\u2022 $it", color = Frost.Muted, fontSize = 12.sp) }
        }
    }
}
