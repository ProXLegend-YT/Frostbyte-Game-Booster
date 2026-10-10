package com.frostbyte.gamebooster.system

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.frostbyte.gamebooster.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Memory boost. Android does NOT let normal apps force-stop other apps. What it does allow (with a normal,
 * no-prompt permission) is asking the system to end other apps' BACKGROUND processes, the same thing it does
 * itself when memory runs low. Foreground apps, your keyboard, launcher and phone app are left alone.
 * We measure free memory before and after and report exactly what changed.
 */
object MemoryBoost {
    data class Result(val targeted: Int, val beforeMb: Long, val afterMb: Long) {
        val freedMb: Long get() = afterMb - beforeMb
        fun summary(): String =
            if (freedMb >= 20) {
                "Ended background processes of $targeted apps. Free memory $beforeMb \u2192 $afterMb MB."
            } else {
                "Asked Android to end background processes of $targeted apps. Free memory barely changed (already clear)."
            }
    }

    private val essential = setOf(
        "com.android.systemui", "com.android.settings", "com.android.phone", "com.android.server.telecom",
        "com.samsung.android.incallui", "com.samsung.android.dialer", "com.google.android.dialer",
    )

    @Suppress("DEPRECATION")
    fun targets(c: Context, exclude: Set<String>): List<String> {
        val pm = c.packageManager
        val home = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
        val ime = Settings.Secure.getString(c.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != c.packageName && it !in exclude && it !in home && it != ime && it !in essential }
    }

    suspend fun sweep(c: Context, exclude: Set<String>): Result = withContext(Dispatchers.Default) {
        val before = DeviceStats.memory(c).availBytes / 1_048_576
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val list = targets(c, exclude)
        for (pkg in list) {
            try {
                am.killBackgroundProcesses(pkg)
            } catch (_: SecurityException) {
                // permission missing on this ROM; nothing more we can do
            }
        }
        delay(1500)   // give the system a moment to reclaim
        val after = DeviceStats.memory(c).availBytes / 1_048_576
        Result(list.size, before, after)
    }
}

/** Optional: lower screen brightness while gaming (less heat, longer play). Needs 'Modify system settings'. Always restored. */
object BrightnessController {
    fun apply(c: Context, pct: Int): Boolean {
        if (!Settings.System.canWrite(c)) return false
        val cr = c.contentResolver
        if (!Prefs.brightOwned(c)) {
            val mode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val value = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            Prefs.setBright(c, true, mode, value)
        }
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (pct * 255 / 100).coerceIn(10, 255))
        return true
    }

    fun restore(c: Context) {
        if (!Prefs.brightOwned(c)) return
        if (Settings.System.canWrite(c)) {
            val cr = c.contentResolver
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, Prefs.savedBrightValue(c))
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Prefs.savedBrightMode(c))
        }
        Prefs.setBright(c, false, Prefs.savedBrightMode(c), Prefs.savedBrightValue(c))
    }
}

/** Optional: pause automatic background sync while gaming (less background work). Restored afterwards. */
object SyncController {
    fun pause(c: Context): Boolean = try {
        if (!Prefs.syncOwned(c)) Prefs.setSync(c, true, ContentResolver.getMasterSyncAutomatically())
        ContentResolver.setMasterSyncAutomatically(false)
        true
    } catch (_: SecurityException) {
        false
    }

    fun restore(c: Context) {
        if (!Prefs.syncOwned(c)) return
        try {
            ContentResolver.setMasterSyncAutomatically(Prefs.savedSync(c))
        } catch (_: SecurityException) {
            // can't restore without permission; leave as is
        }
        Prefs.setSync(c, false, Prefs.savedSync(c))
    }
}
