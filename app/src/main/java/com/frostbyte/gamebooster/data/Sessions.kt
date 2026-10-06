package com.frostbyte.gamebooster.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

const val NO_TEMP = -999f

data class Session(
    val gameId: String,
    val gameName: String,
    val startMs: Long,
    val endMs: Long,
    val batStart: Int,      // -1 = unknown
    val batEnd: Int,
    val tempStart: Float,   // NO_TEMP = unknown
    val tempEnd: Float,
    val tempMax: Float,
) {
    val durationMs: Long get() = endMs - startMs
}

/** Gaming history, stored locally only. */
object SessionStore {
    fun all(c: Context): List<Session> {
        val out = mutableListOf<Session>()
        try {
            val arr = JSONArray(Prefs.sessionsJson(c))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Session(
                    o.getString("id"), o.getString("name"), o.getLong("s"), o.getLong("e"),
                    o.optInt("bs", -1), o.optInt("be", -1),
                    o.optDouble("ts", NO_TEMP.toDouble()).toFloat(),
                    o.optDouble("te", NO_TEMP.toDouble()).toFloat(),
                    o.optDouble("tm", NO_TEMP.toDouble()).toFloat(),
                )
            }
        } catch (_: Exception) {
            // corrupted store: start fresh rather than crash
        }
        return out
    }

    fun add(c: Context, s: Session) {
        val list = (all(c) + s).takeLast(200)
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.gameId).put("name", it.gameName).put("s", it.startMs).put("e", it.endMs)
                    .put("bs", it.batStart).put("be", it.batEnd)
                    .put("ts", it.tempStart.toDouble()).put("te", it.tempEnd.toDouble()).put("tm", it.tempMax.toDouble()),
            )
        }
        Prefs.setSessionsJson(c, arr.toString())
    }

    fun clear(c: Context) = Prefs.setSessionsJson(c, "[]")
}
