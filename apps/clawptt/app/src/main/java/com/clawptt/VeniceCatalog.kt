package com.clawptt

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches Venice.ai's live TTS model + voice catalog (GET /models?type=tts) so the settings UI can
 * offer real pickers instead of hand-typed model/voice strings. Cached in memory per API key.
 */
object VeniceCatalog {

    data class Model(val id: String, val voices: List<String>, val defaultVoice: String)

    @Volatile private var cache: List<Model>? = null
    @Volatile private var cacheKey: String = ""

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())

    fun clear() { cache = null; cacheKey = "" }

    /** Loads the TTS catalog (cached). `onResult` runs on the main thread; null = fetch failed. */
    fun load(key: String, onResult: (List<Model>?) -> Unit) {
        cache?.let { if (cacheKey == key) { onResult(it); return } }
        Thread {
            val models = runCatching {
                val req = Request.Builder()
                    .url("https://api.venice.ai/api/v1/models?type=tts")
                    .addHeader("Authorization", "Bearer $key").get().build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val arr = JSONObject(resp.body!!.string()).getJSONArray("data")
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.getJSONObject(i)
                        val spec = o.optJSONObject("model_spec") ?: return@mapNotNull null
                        val vArr = spec.optJSONArray("voices") ?: return@mapNotNull null
                        val voices = (0 until vArr.length()).map { vArr.getString(it) }
                        if (voices.isEmpty()) return@mapNotNull null
                        Model(o.getString("id"), voices, spec.optString("default_voice", voices.first()))
                    }.sortedBy { it.id }
                }
            }.getOrNull()
            if (models != null) { cache = models; cacheKey = key }
            main.post { onResult(models) }
        }.start()
    }
}
