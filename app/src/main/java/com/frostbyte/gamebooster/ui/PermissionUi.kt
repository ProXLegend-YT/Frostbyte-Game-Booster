package com.frostbyte.gamebooster.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.frostbyte.gamebooster.system.Perms
import com.frostbyte.gamebooster.ui.theme.Frost

/** Increments every time the app returns to the foreground (e.g. after the user visits a Settings screen). */
@Composable
fun rememberResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

@Composable
fun PermissionRow(title: String, why: String, granted: Boolean, required: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title + if (required) "" else "  (optional)", color = Frost.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(why, color = Frost.Muted, fontSize = 11.sp)
        }
        if (granted) {
            Text("\u2713 On", color = Frost.Good, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        } else {
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(containerColor = Frost.Cyan, contentColor = Frost.Bg),
            ) { Text("Allow", fontWeight = FontWeight.Bold) }
        }
    }
}

/** Every permission FrostByte can use, with plain-English reasons. Nothing is requested until you tap Allow. */
@Composable
fun PermissionList() {
    val context = LocalContext.current
    val resume = rememberResumeTick()
    var local by remember { mutableIntStateOf(0) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { local++ }

    // reading these makes the checks re-run after returning from Settings
    @Suppress("UNUSED_VARIABLE") val refresh = resume + local
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PermissionRow(
            "Notifications", "Shows the 'Game Booster' notification you tap to open the panel.",
            Perms.notifications(context), true,
        ) {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else Perms.openNotificationSettings(context)
        }
        PermissionRow(
            "Display over other apps", "Lets the side panel and Touch Lock appear on top of your game.",
            Perms.overlay(context), true,
        ) { Perms.openOverlay(context) }
        PermissionRow(
            "Do Not Disturb access", "Silences notifications/calls while you play, and restores your setting after.",
            Perms.dnd(context), false,
        ) { Perms.openDnd(context) }
        PermissionRow(
            "Usage access", "Detects when the game closes (to end Gaming Mode) and shows 'last played'.",
            Perms.usage(context), false,
        ) { Perms.openUsage(context) }
    }
}
