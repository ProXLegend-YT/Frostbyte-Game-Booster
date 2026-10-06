package com.frostbyte.gamebooster.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
}
