package com.clawptt

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The active conversation thread shown on the dedicated chat page: a chronological
 * (oldest -> newest) list of {role, text, time}. Persists across app restarts so the page
 * can be re-seeded, and is cleared by "New chat" (alongside Config.newConversation()). Kept
 * separate from [History] (the all-time Q&A log) so it can be wiped per session. Capped.
 */
object Conversation {

    private const val PREFS = "clawptt_conversation"
    private const val KEY = "messages"
    private const val CAP = 200

    const val USER = "user"
    const val ASSISTANT = "assistant"

    data class Msg(val role: String, val text: String, val time: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun add(ctx: Context, role: String, text: String) {
        if (text.isBlank()) return
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        arr.put(JSONObject().put("r", role).put("t", text).put("ts", System.currentTimeMillis()))
        // Trim from the front if over cap.
        val trimmed = if (arr.length() <= CAP) arr else JSONArray().apply {
            for (i in (arr.length() - CAP) until arr.length()) put(arr.getJSONObject(i))
        }
        prefs(ctx).edit().putString(KEY, trimmed.toString()).apply()
    }

    fun all(ctx: Context): List<Msg> {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Msg(o.optString("r", USER), o.optString("t"), o.optLong("ts"))
        }
    }

    fun clear(ctx: Context) = prefs(ctx).edit().remove(KEY).apply()
}
