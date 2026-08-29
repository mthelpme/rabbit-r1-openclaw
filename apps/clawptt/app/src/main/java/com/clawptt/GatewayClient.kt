package com.clawptt

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to the OpenClaw gateway's OpenAI-compatible /v1/chat/completions.
 * Auth: Bearer <gateway token>. Base URL is the Tailscale Serve HTTPS host.
 */
class GatewayClient(private val cfg: Config) {

    private val http = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // No overall call timeout for streaming; abort only if the stream stalls for readTimeout.
    // readTimeout is the max gap between bytes — during a long tool-using task NO bytes flow until
    // the agent emits its first token, so this caps how long that first-token wait may be (5 min).
    private val streamHttp = OkHttpClient.Builder()
        .callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    @Volatile private var currentCall: okhttp3.Call? = null

    /** Aborts the in-flight chat request (drops the connection to OpenClaw). */
    fun cancel() { runCatching { currentCall?.cancel() } }

    /**
     * Streams the reply via SSE. Calls onDelta for each token as it arrives; returns the full
     * text when the stream ends. Blocking; call off the main thread.
     */
    fun chatStream(userText: String, onDelta: (String) -> Unit): String {
        val body = JSONObject().apply {
            put("model", cfg.model)
            put("stream", true)
            put("user", cfg.sessionKey)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", cfg.systemPrompt))
                put(JSONObject().put("role", "user").put("content", userText))
            })
        }
        val builder = Request.Builder()
            .url("${cfg.baseUrl}/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${cfg.token}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (cfg.underlyingModel.isNotBlank()) builder.addHeader("x-openclaw-model", cfg.underlyingModel)

        val call = streamHttp.newCall(builder.build()); currentCall = call
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("chat HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
            val full = StringBuilder()
            val t0 = System.currentTimeMillis()
            var firstAt = -1L; var lastDeltaAt = -1L; var stop = ""
            val lastActivity = java.util.concurrent.atomic.AtomicLong(t0)
            val gotContent = java.util.concurrent.atomic.AtomicBoolean(false)
            val endsComplete = java.util.concurrent.atomic.AtomicBoolean(false)
            val streamDone = java.util.concurrent.atomic.AtomicBoolean(false)
            val idleCancelled = java.util.concurrent.atomic.AtomicBoolean(false)

            // OpenClaw streams the visible reply quickly but can hold the SSE open ~30-60s before
            // it emits finish_reason/[DONE]. Don't make TTS wait on that: once the text has settled
            // (ends on a full sentence, then QUIET_MS of silence) we consider the reply done. A
            // hard cap covers a reply that trails off mid-sentence.
            Thread {
                while (!streamDone.get()) {
                    try { Thread.sleep(250) } catch (_: InterruptedException) { break }
                    if (!gotContent.get()) continue
                    val idle = System.currentTimeMillis() - lastActivity.get()
                    if ((idle > QUIET_MS && endsComplete.get()) || idle > IDLE_HARD_CAP_MS) {
                        idleCancelled.set(true); runCatching { call.cancel() }; break
                    }
                }
            }.apply { isDaemon = true; start() }

            try {
                val reader = resp.body!!.charStream().buffered()
                run stream@{
                    reader.lineSequence().forEach { raw ->
                        val line = raw.trim()
                        if (!line.startsWith("data:")) return@forEach
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") { stop = "[DONE]"; return@stream }
                        if (data.isEmpty()) return@forEach
                        val choice = runCatching {
                            JSONObject(data).getJSONArray("choices").getJSONObject(0)
                        }.getOrNull() ?: return@forEach
                        val delta = choice.optJSONObject("delta")?.optString("content", "").orEmpty()
                        if (delta.isNotEmpty()) {
                            val now = System.currentTimeMillis()
                            if (firstAt < 0) firstAt = now
                            lastDeltaAt = now
                            full.append(delta); onDelta(delta)
                            lastActivity.set(now); gotContent.set(true)
                            val tail = full.toString().trimEnd()
                            endsComplete.set(tail.isNotEmpty() && tail.last() in TERMINALS)
                        }
                        // finish_reason ends it instantly if it arrives before the quiet timeout.
                        if (!choice.isNull("finish_reason")) { stop = choice.optString("finish_reason"); return@stream }
                    }
                }
            } catch (e: Exception) {
                if (!idleCancelled.get()) { streamDone.set(true); throw e }  // real error, not our quiet-cancel
                stop = "idle"
            } finally {
                streamDone.set(true)
            }
            runCatching { call.cancel() }   // drop the lingering connection immediately
            android.util.Log.i("ClawPTT", "stream end via '$stop' firstTok=${if (firstAt<0) -1 else firstAt-t0}ms " +
                "lastDelta=${if (lastDeltaAt<0) -1 else lastDeltaAt-t0}ms total=${System.currentTimeMillis()-t0}ms len=${full.length}")
            return full.toString()
        }
    }

    companion object {
        private const val QUIET_MS = 2000L          // silence after a full sentence = reply done
        private const val IDLE_HARD_CAP_MS = 7000L  // backstop when the reply lacks terminal punctuation
        private val TERMINALS = charArrayOf('.', '!', '?', '…', '"', '”', ')', ']', '’')
    }

    /** Sends the user transcript, returns the assistant's reply text. Blocking; call off main thread. */
    fun chat(userText: String): String {
        android.util.Log.i("ClawPTT", "chat POST (session=${cfg.sessionKey}) text=\"${userText.take(60)}\"")
        val body = JSONObject().apply {
            put("model", cfg.model)
            put("stream", false)
            put("user", cfg.sessionKey)   // stable OpenClaw session -> one continuous conversation
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", cfg.systemPrompt))
                put(JSONObject().put("role", "user").put("content", userText))
            })
        }
        val builder = Request.Builder()
            .url("${cfg.baseUrl}/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${cfg.token}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (cfg.underlyingModel.isNotBlank()) builder.addHeader("x-openclaw-model", cfg.underlyingModel)
        val req = builder.build()

        val call = http.newCall(req); currentCall = call
        call.execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("chat HTTP ${resp.code}: ${text.take(300)}")
            val json = JSONObject(text)
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        }
    }
}
