package com.frostbyte.gamebooster

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import com.frostbyte.gamebooster.data.InstalledGame
import com.frostbyte.gamebooster.data.Prefs
import com.frostbyte.gamebooster.service.GamingService
import com.frostbyte.gamebooster.system.BrightnessController
import com.frostbyte.gamebooster.system.DndController
import com.frostbyte.gamebooster.system.SyncController
import com.frostbyte.gamebooster.ui.AfkScreen
import com.frostbyte.gamebooster.ui.AppManagerScreen
import com.frostbyte.gamebooster.ui.CleanAndPlayScreen
import com.frostbyte.gamebooster.ui.GamesScreen
import com.frostbyte.gamebooster.ui.HistoryScreen
import com.frostbyte.gamebooster.ui.HomeScreen
import com.frostbyte.gamebooster.ui.PerformanceScreen
import com.frostbyte.gamebooster.ui.SettingsScreen
import com.frostbyte.gamebooster.ui.theme.Frost
import com.frostbyte.gamebooster.ui.theme.FrostByteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // if the app was killed mid-game, put the user's own settings back
        if (GamingService.activeGameName == null) {
            DndController.restore(this)
            BrightnessController.restore(this)
            SyncController.restore(this)
        }
        Frost.applyAmoled(Prefs.amoled(this))
        window.setBackgroundDrawable(ColorDrawable(Frost.Bg.toArgb()))
        setContent { FrostByteTheme { FrostByteApp() } }
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Home", Icons.Filled.Home),
    Tab("Games", Icons.Filled.PlayArrow),
    Tab("Performance", Icons.Filled.Info),
    Tab("History", Icons.Filled.Refresh),
    Tab("Settings", Icons.Filled.Settings),
)

@Composable
fun FrostByteApp() {
    var selected by remember { mutableIntStateOf(0) }
    var sub by remember { mutableStateOf<String?>(null) }   // "afk" | "manager"
    var cleanGame by remember { mutableStateOf<InstalledGame?>(null) }
    BackHandler(enabled = sub != null || cleanGame != null) { sub = null; cleanGame = null }
    Scaffold(
        containerColor = Frost.Bg,
        bottomBar = {
            NavigationBar(containerColor = Frost.Card) {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Frost.Bg,
                            selectedTextColor = Frost.Cyan,
                            indicatorColor = Frost.Cyan,
                            unselectedIconColor = Frost.Muted,
                            unselectedTextColor = Frost.Muted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).safeDrawingPadding()) {
            val cg = cleanGame
            when {
                cg != null -> CleanAndPlayScreen(cg) { cleanGame = null }
                sub == "afk" -> AfkScreen { sub = null }
                sub == "manager" -> AppManagerScreen { sub = null }
                else -> when (selected) {
                    0 -> HomeScreen(onOpenGames = { selected = 1 }, onOpenAfk = { sub = "afk" }, onOpenManager = { sub = "manager" }, onGuidedClean = { cleanGame = it })
                    1 -> GamesScreen(onGuidedClean = { cleanGame = it })
                    2 -> PerformanceScreen()
                    3 -> HistoryScreen()
                    else -> SettingsScreen()
                }
            }
        }
    }
}
