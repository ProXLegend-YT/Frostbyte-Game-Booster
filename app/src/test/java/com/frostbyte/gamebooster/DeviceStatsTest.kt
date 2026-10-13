package com.frostbyte.gamebooster

import com.frostbyte.gamebooster.system.BatteryInfo
import com.frostbyte.gamebooster.system.MemoryStats
import com.frostbyte.gamebooster.system.ThermalLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceStatsTest {
    @Test fun thermalLevels() {
        assertEquals(ThermalLevel.COOL, BatteryInfo(50, false, 34f).thermal)
        assertEquals(ThermalLevel.WARM, BatteryInfo(50, false, 39f).thermal)
        assertEquals(ThermalLevel.HOT, BatteryInfo(50, false, 43f).thermal)
        assertEquals(ThermalLevel.CRITICAL, BatteryInfo(50, false, 47f).thermal)
    }
    @Test fun unavailableTempIsNeverFaked() {
        assertNull(BatteryInfo(50, false, null).thermal)
    }
    @Test fun memoryPercent() {
        assertEquals(75, MemoryStats(availBytes = 1000, totalBytes = 4000).usedPercent)
    }
}

class UtilTest {
    @Test fun remainingNeedsRealData() {
        org.junit.Assert.assertNull(com.frostbyte.gamebooster.util.Estimates.remainingMs(90, 89, 20 * 60_000L, false))
        org.junit.Assert.assertNull(com.frostbyte.gamebooster.util.Estimates.remainingMs(90, 88, 2 * 60_000L, false))
        org.junit.Assert.assertNull(com.frostbyte.gamebooster.util.Estimates.remainingMs(90, 88, 20 * 60_000L, true))
        org.junit.Assert.assertNull(com.frostbyte.gamebooster.util.Estimates.remainingMs(null, 88, 20 * 60_000L, false))
    }
    @Test fun remainingMath() {
        // 2% in 10 min -> 88% left -> 440 min
        assertEquals(26_400_000L, com.frostbyte.gamebooster.util.Estimates.remainingMs(90, 88, 600_000L, false))
    }
    @Test fun durationFormat() {
        assertEquals("1h 5m", com.frostbyte.gamebooster.util.Fmt.duration(3_900_000L))
        assertEquals("42m", com.frostbyte.gamebooster.util.Fmt.duration(42 * 60_000L))
    }
    @Test fun agoFormat() {
        assertEquals("just now", com.frostbyte.gamebooster.util.Fmt.ago(100_000L, 90_000L))
        assertEquals("5m ago", com.frostbyte.gamebooster.util.Fmt.ago(600_000L, 300_000L))
        assertEquals("2h ago", com.frostbyte.gamebooster.util.Fmt.ago(8_000_000L, 800_000L))
    }
}

class PresetsTest {
    @Test fun presetsAreSane() {
        val ids = com.frostbyte.gamebooster.data.PanelPresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        com.frostbyte.gamebooster.data.PanelPresets.all.forEach {
            org.junit.Assert.assertTrue(it.cards.isNotEmpty())
            org.junit.Assert.assertTrue(it.shortcuts.isNotEmpty())
            org.junit.Assert.assertTrue(it.opacity in 0.4f..0.95f)
        }
    }
}

class MemoryBoostTest {
    @Test fun summaryIsHonest() {
        val gained = com.frostbyte.gamebooster.system.MemoryBoost.Result(12, 1000, 1400)
        org.junit.Assert.assertEquals(400L, gained.freedMb)
        org.junit.Assert.assertTrue(gained.summary().contains("1000"))
        val none = com.frostbyte.gamebooster.system.MemoryBoost.Result(12, 1000, 1005)
        org.junit.Assert.assertTrue(none.summary().contains("barely changed"))
    }
}

class ClickTargetTest {
    @Test fun secondsFollowMode() {
        val t = com.frostbyte.gamebooster.data.ClickTarget(0, true, 100, 200, com.frostbyte.gamebooster.data.ClickTarget.MODE_NETWORK, 60, 5, 60)
        assertEquals(5, t.seconds)
        assertEquals(60, t.copy(mode = com.frostbyte.gamebooster.data.ClickTarget.MODE_REPEAT).seconds)
        org.junit.Assert.assertTrue(t.placed)
        org.junit.Assert.assertFalse(t.copy(x = -1).placed)
    }
}
