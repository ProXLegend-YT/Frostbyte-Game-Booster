package com.frostbyte.gamebooster.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object NetworkProbe {
    fun label(c: Context): String {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "Offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }
    }

    /** Real TCP connect time to 1.1.1.1:443 in ms, or null if it can't be measured. */
    suspend fun pingMs(): Int? = withContext(Dispatchers.IO) {
        try {
            Socket().use { s ->
                val t0 = SystemClock.elapsedRealtime()
                s.connect(InetSocketAddress("1.1.1.1", 443), 2000)
                (SystemClock.elapsedRealtime() - t0).toInt()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun quality(recent: List<Int?>): String {
        val ok = recent.filterNotNull()
        if (ok.isEmpty()) return "Ping unavailable"
        val avg = ok.average()
        val jitter = (ok.maxOrNull() ?: 0) - (ok.minOrNull() ?: 0)
        return when {
            ok.size >= 3 && jitter > 60 -> "Unstable"
            avg < 60 -> "Good"
            avg < 120 -> "Fair"
            else -> "Poor"
        }
    }
}
