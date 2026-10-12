package com.frostbyte.gamebooster.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.PowerManager

enum class ThermalLevel(val label: String) {
    COOL("Cool"), WARM("Warm"), HOT("Hot"), CRITICAL("Critical")
}

data class BatteryInfo(
    val percent: Int?,          // null = unavailable
    val charging: Boolean,
    val tempC: Float?,          // null = unavailable (never faked)
) {
    val thermal: ThermalLevel?
        get() = tempC?.let {
            when {
                it < 37f -> ThermalLevel.COOL
                it < 42f -> ThermalLevel.WARM
                it < 45f -> ThermalLevel.HOT
                else -> ThermalLevel.CRITICAL
            }
        }
}

data class MemoryStats(val availBytes: Long, val totalBytes: Long) {
    val usedPercent: Int
        get() = if (totalBytes <= 0) 0 else (((totalBytes - availBytes) * 100) / totalBytes).toInt()
}

/** Everything here reads real Android values. If a value is missing we return null, never a made-up number. */
object DeviceStats {

    fun battery(context: Context): BatteryInfo {
        val i: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val rawTemp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE

        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else null
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val tempC = if (rawTemp == Int.MIN_VALUE) null else rawTemp / 10f
        return BatteryInfo(percent, charging, tempC)
    }

    fun memory(context: Context): MemoryStats {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return MemoryStats(info.availMem, info.totalMem)
    }

    fun powerSave(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode

    /** Android's own thermal throttling level: 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown. */
    fun thermalStatus(context: Context): Int =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus

    fun throttleLabel(status: Int): String = when (status) {
        0 -> "None"
        1 -> "Light"
        2 -> "Moderate"
        3 -> "Severe"
        4 -> "Critical"
        5 -> "Emergency"
        else -> "Shutdown"
    }

    /** 1.0 = Android starts throttling. Only on Android 11+ and only if the device reports it; otherwise null. */
    fun thermalHeadroom(context: Context): Float? {
        if (Build.VERSION.SDK_INT < 30) return null
        val h = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).getThermalHeadroom(10)
        return if (h.isNaN()) null else h
    }

    fun freeStoragePercent(): Int {
        val st = StatFs(Environment.getDataDirectory().path)
        val total = st.totalBytes
        return if (total <= 0L) 100 else (st.availableBytes * 100 / total).toInt()
    }
}
