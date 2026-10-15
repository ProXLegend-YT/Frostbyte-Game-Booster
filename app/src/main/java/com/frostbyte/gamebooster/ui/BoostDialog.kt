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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frostbyte.gamebooster.data.Games
import com.frostbyte.gamebooster.data.InstalledGame
import com.frostbyte.gamebooster.data.Prefs
import com.frostbyte.gamebooster.service.GamingService
import com.frostbyte.gamebooster.ui.theme.Frost

/** One-Tap Boost: shows exactly what will be active, lets you grant what's missing, then launches the game. */
@Composable
fun BoostDialog(game: InstalledGame, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val profile = remember { Prefs.profile(context, game.def.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Frost.Card,
        titleContentColor = Frost.Text,
        textContentColor = Frost.Muted,
        title = { Text("Boost & Play \u00B7 ${game.label}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Profile: ${profile.label}. ${profile.blurb}.", color = Frost.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "FrostByte can't raise CPU speed, add RAM or cool the phone. It silences interruptions, keeps the screen awake if you chose that, and gives you the in-game panel.",
                    color = Frost.Muted, fontSize = 11.sp,
                )
                Spacer(Modifier.height(8.dp))
                PermissionList()
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    GamingService.start(context, game)
                    if (!Games.launch(context, game)) {
                        GamingService.stop(context)
                        Toast.makeText(context, "Couldn't open ${game.label}", Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Frost.Cyan, contentColor = Frost.Bg),
            ) { Text("Launch ${game.label.substringBefore(' ')}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Frost.Muted) } },
    )
}
