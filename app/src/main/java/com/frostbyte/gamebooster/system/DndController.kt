package com.frostbyte.gamebooster.system

import android.app.NotificationManager
import android.content.Context
import com.frostbyte.gamebooster.data.Prefs

/**
 * Do Not Disturb, only with the user's explicit permission, and always restored afterwards.
 * "Alarms only" is used for call-blocking so game audio (media) keeps playing.
 * Android cannot block calls without also silencing notifications.
 */
object DndController {
    fun apply(c: Context, blockNotifications: Boolean, blockCalls: Boolean): Boolean {
        if (!Perms.dnd(c)) return false
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val target = when {
            blockCalls -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            blockNotifications -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else -> null
        }
        if (target == null) {
            restore(c)
            return true
        }
        if (!Prefs.dndOwned(c)) {
            val current = nm.currentInterruptionFilter
            Prefs.setDnd(c, owned = true, saved = if (current == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) NotificationManager.INTERRUPTION_FILTER_ALL else current)
        }
        nm.setInterruptionFilter(target)
        return true
    }

    fun restore(c: Context) {
        if (!Prefs.dndOwned(c)) return
        if (Perms.dnd(c)) {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.setInterruptionFilter(Prefs.savedDnd(c))
        }
        Prefs.setDnd(c, owned = false, saved = Prefs.savedDnd(c))
    }
}
