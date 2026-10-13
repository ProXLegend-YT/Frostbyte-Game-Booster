package com.frostbyte.gamebooster.system

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.frostbyte.gamebooster.service.ClickerService

/** Permission checks + the official Android screens where the user grants them. Nothing is changed silently. */
object Perms {
    fun overlay(c: Context): Boolean = Settings.canDrawOverlays(c)

    fun dnd(c: Context): Boolean =
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted

    fun usage(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun notifications(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(c).areNotificationsEnabled()
        }

    /** Is the FrostByte Auto Clicker accessibility service switched on in Android settings? */
    fun accessibility(c: Context): Boolean {
        val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = ComponentName(c, ClickerService::class.java)
        return enabled.split(':').any { it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true) }
    }

    fun openAccessibility(c: Context) = open(c, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun writeSettings(c: Context): Boolean = Settings.System.canWrite(c)

    fun open(c: Context, intent: Intent) {
        try {
            c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // Settings screen missing on this ROM; ignore
        }
    }

    fun openOverlay(c: Context) =
        open(c, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}")))

    fun openWriteSettings(c: Context) =
        open(c, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${c.packageName}")))

    fun openDnd(c: Context) = open(c, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
    fun openUsage(c: Context) = open(c, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    fun openNotificationSettings(c: Context) =
        open(c, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName))
    fun openBatterySaver(c: Context) = open(c, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
    fun openAppInfo(c: Context, pkg: String) =
        open(c, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
}
