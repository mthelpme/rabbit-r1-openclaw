package com.clawptt

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Named, resumable conversation threads. Each thread is keyed by its OpenClaw session key
 * (Config.sessionKey) so switching the active key resumes that server-side conversation. Messages
 * for a key live under `msgs_<key>`; a lightweight `index` tracks {key, title, updatedAt, count}
 * for the switcher UI. Auto-titled from the first user message. Kept separate from [History] (the
 * all-time Q&A log). "New chat" mints a new key (Config.newConversation) and leaves old threads
 * intact and resumable — they're only removed by an explicit delete.
 */
object Conversation {

    private const val PREFS = "clawptt_conversation"
    private const val INDEX = "index"
    private const val CAP = 200
    private const val TITLE_MAX = 42

    const val USER = "user"
    const val ASSISTANT = "assistant"

    data class Msg(val role: String, val text: String, val time: Long)
    data class Conv(val key: String, val title: String, val updatedAt: Long, val count: Int)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun msgKey(key: String) = "msgs_$key"

    fun add(ctx: Context, key: String, role: String, text: String) {
        if (text.isBlank() || key.isBlank()) return
        val arr = JSONArray(prefs(ctx).getString(msgKey(key), "[]"))
        arr.put(JSONObject().put("r", role).put("t", text).put("ts", System.currentTimeMillis()))
        val trimmed = if (arr.length() <= CAP) arr else JSONArray().apply {
            for (i in (arr.length() - CAP) until arr.length()) put(arr.getJSONObject(i))
        }
        prefs(ctx).edit().putString(msgKey(key), trimmed.toString()).apply()
        touchIndex(ctx, key, role, text, trimmed.length())
    }

    fun all(ctx: Context, key: String): List<Msg> {
        val arr = JSONArray(prefs(ctx).getString(msgKey(key), "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Msg(o.optString("r", USER), o.optString("t"), o.optLong("ts"))
        }
    }

    /** All threads, most-recently-updated first. */
    fun list(ctx: Context): List<Conv> {
        val idx = JSONArray(prefs(ctx).getString(INDEX, "[]"))
        return (0 until idx.length()).map {
            val o = idx.getJSONObject(it)
            Conv(o.getString("k"), o.optString("title").ifBlank { "Untitled" }, o.optLong("ts"), o.optInt("n"))
        }.sortedByDescending { it.updatedAt }
    }

    fun title(ctx: Context, key: String): String =
        list(ctx).firstOrNull { it.key == key }?.title ?: "New conversation"

    fun rename(ctx: Context, key: String, title: String) {
        val idx = JSONArray(prefs(ctx).getString(INDEX, "[]"))
        for (i in 0 until idx.length()) {
            if (idx.getJSONObject(i).getString("k") == key) {
                idx.getJSONObject(i).put("title", title.take(TITLE_MAX)); break
            }
        }
        prefs(ctx).edit().putString(INDEX, idx.toString()).apply()
    }

    /** Delete a single thread (messages + index entry). */
    fun clear(ctx: Context, key: String) {
        val idx = JSONArray(prefs(ctx).getString(INDEX, "[]"))
        val kept = JSONArray()
        for (i in 0 until idx.length()) if (idx.getJSONObject(i).getString("k") != key) kept.put(idx.getJSONObject(i))
        prefs(ctx).edit().remove(msgKey(key)).putString(INDEX, kept.toString()).apply()
    }

    private fun touchIndex(ctx: Context, key: String, role: String, text: String, count: Int) {
        val idx = JSONArray(prefs(ctx).getString(INDEX, "[]"))
        var found: JSONObject? = null
        for (i in 0 until idx.length()) if (idx.getJSONObject(i).getString("k") == key) { found = idx.getJSONObject(i); break }
        val entry = found ?: JSONObject().put("k", key).put("title", "").also { idx.put(it) }
        entry.put("ts", System.currentTimeMillis()).put("n", count)
        if (entry.optString("title").isBlank() && role == USER) {
            entry.put("title", text.trim().replace('\n', ' ').take(TITLE_MAX))
        }
        prefs(ctx).edit().putString(INDEX, idx.toString()).apply()
    }
}
