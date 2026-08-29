package com.clawptt

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local conversation log: newest-first list of {time, question, answer}. Capped. */
object History {

    private const val PREFS = "clawptt_history"
    private const val KEY = "turns"
    private const val CAP = 100

    data class Turn(val time: Long, val question: String, val answer: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun add(ctx: Context, question: String, answer: String) {
        if (question.isBlank() && answer.isBlank()) return
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        val next = JSONArray()
        next.put(JSONObject().put("t", System.currentTimeMillis()).put("q", question).put("a", answer))
        for (i in 0 until minOf(arr.length(), CAP - 1)) next.put(arr.getJSONObject(i))
        prefs(ctx).edit().putString(KEY, next.toString()).apply()
    }

    fun all(ctx: Context): List<Turn> {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Turn(o.optLong("t"), o.optString("q"), o.optString("a"))
        }
    }

    fun clear(ctx: Context) = prefs(ctx).edit().remove(KEY).apply()
}
