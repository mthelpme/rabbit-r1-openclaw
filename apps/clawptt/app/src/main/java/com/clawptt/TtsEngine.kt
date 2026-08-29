package com.clawptt

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Speaks the assistant reply.
 *  - SYSTEM: Android TextToSpeech (routes to the system engine, e.g. SherpaTTS). Default.
 *  - ELEVENLABS: fetches MP3 from ElevenLabs and plays it.
 *
 * The system engine can take a few seconds to bind (SherpaTTS loads an ONNX voice on init),
 * so text is queued and spoken once bound; a dropped binding self-heals.
 */
class TtsEngine(private val ctx: Context, private val cfg: Config) {

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var pending: String? = null
    private var player: MediaPlayer? = null
    private var kokoroCall: okhttp3.Call? = null
    @Volatile private var track: AudioTrack? = null
    // Generous timeouts: on cellular we download the WHOLE clip (Venice streaming:false holds the
    // connection during generation), so 30s was too short for long replies. callTimeout caps a
    // truly stuck request.
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Streaming-PCM jitter buffer (see playPcmStream). PREBUFFER = audio banked before playback
    // starts; BUFFER = AudioTrack cushion that absorbs network stalls on cellular / long replies.
    private val PREBUFFER_MS = 1200L
    private val BUFFER_MS = 5000

    var onDone: (() -> Unit)? = null

    fun init(onReady: () -> Unit = {}) {
        tts = TextToSpeech(ctx) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                applyVoice()  // respect the user's chosen voice; don't force a locale
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (isFinal(utteranceId)) onDone?.invoke() }
                    @Deprecated("deprecated") override fun onError(utteranceId: String?) { if (isFinal(utteranceId)) onDone?.invoke() }
                })
                android.util.Log.i("ClawPTT", "TTS ready engine=${tts?.defaultEngine} voice=${tts?.voice?.name} locale=${tts?.voice?.locale}")
                pending?.let { val t = it; pending = null; sysSpeak(t) }
            }
            onReady()
        }
    }

    fun isReady() = ready
    private fun isFinal(id: String?) = id == "clawptt" || id == "clawptt_last"

    /** For ELEVENLABS this blocks on the network; call off the main thread. */
    fun speak(text: String) {
        when (cfg.ttsMode) {
            Config.TtsMode.SYSTEM ->
                if (ready) sysSpeak(text) else { pending = text; if (tts == null) init() }
            Config.TtsMode.ELEVENLABS -> speakEleven(text)
            Config.TtsMode.KOKORO -> speakKokoro(text)
            Config.TtsMode.VENICE -> speakVenice(text)
        }
    }

    /**
     * Streaming speak: enqueue a sentence as it arrives (SYSTEM voice). The `last` chunk fires
     * onDone. ElevenLabs speaks the full text once, on the last chunk.
     */
    fun enqueue(text: String, last: Boolean) {
        when (cfg.ttsMode) {
            Config.TtsMode.SYSTEM -> {
                if (text.isBlank() && !last) return
                val id = if (last) "clawptt_last" else "clawptt_chunk"
                tts?.speak(text.ifBlank { " " }, TextToSpeech.QUEUE_ADD, null, id)
            }
            Config.TtsMode.ELEVENLABS -> if (last) speakEleven(text)
            Config.TtsMode.KOKORO -> if (last) speakKokoro(text)
            Config.TtsMode.VENICE -> if (last) speakVenice(text)
        }
    }

    /**
     * Cellular / metered links can't sustain real-time PCM streaming without choppiness (the audio
     * arrives at ~playback speed so the jitter buffer never builds a real cushion, and any stall
     * starves the AudioTrack). On those, buffer the whole clip and play it via MediaPlayer instead —
     * smooth, at the cost of slightly higher time-to-first-audio.
     */
    private fun metered(): Boolean = runCatching {
        ctx.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false
    }.getOrDefault(false)

    /** Streams 24 kHz PCM from the self-hosted Kokoro service straight to an AudioTrack (gapless). */
    private fun speakKokoro(text: String) {
        if (cfg.bufferOnCellular && metered()) { playFile(download(kokoroWavReq(text), File(ctx.cacheDir, "reply.wav"))); return }
        val payload = JSONObject()
            .put("model", "kokoro").put("input", text).put("voice", cfg.kokoroVoice)
            .put("response_format", "pcm").put("stream", true).toString()
        val req = Request.Builder()
            .url("${cfg.ttsServiceUrl}/v1/audio/speech")
            .addHeader("Authorization", "Bearer ${cfg.ttsServiceToken}")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val call = http.newCall(req); kokoroCall = call
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Kokoro HTTP ${resp.code}")
            playPcmStream(resp.body!!.byteStream(), 24000, 1)
        }
        onDone?.invoke()
    }

    /**
     * Streams s16le PCM from Venice (streaming:true) straight to an AudioTrack, so playback starts
     * on the first chunk (~1-2s) instead of after the whole clip renders. Assumes 24 kHz mono —
     * correct for the tts-kokoro model (the default). A different Venice model may use another rate;
     * if audio sounds sped-up/garbled after switching models, that's the sample rate to revisit.
     */
    private fun speakVenice(text: String) {
        // Stream raw PCM only for the kokoro model (known 24 kHz). On metered links, or for any OTHER
        // Venice model (ElevenLabs, xAI, etc. — unknown/variable sample rate), buffer the mp3 and let
        // MediaPlayer handle the rate correctly.
        if ((cfg.bufferOnCellular && metered()) || !cfg.veniceModel.contains("kokoro", ignoreCase = true)) {
            playFile(download(veniceReq(text), File(ctx.cacheDir, "reply.mp3"))); return
        }
        val payload = JSONObject()
            .put("input", text).put("model", cfg.veniceModel).put("voice", cfg.veniceVoice)
            .put("response_format", "pcm").put("streaming", true).toString()
        val req = Request.Builder()
            .url("https://api.venice.ai/api/v1/audio/speech")
            .addHeader("Authorization", "Bearer ${cfg.veniceKey}")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val call = http.newCall(req); kokoroCall = call   // reuse the stream-cancel handle for stop()
        val t0 = System.currentTimeMillis()
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Venice HTTP ${resp.code}: ${resp.body?.string()?.take(160)}")
            android.util.Log.i("ClawPTT", "Venice stream headers in ${System.currentTimeMillis() - t0}ms")
            playPcmStream(resp.body!!.byteStream(), 24000, 1)
        }
        onDone?.invoke()
    }

    /**
     * Builds an AudioTrack and pumps s16le PCM from `stream` into it gaplessly, then releases.
     * A jitter buffer keeps playback smooth over bursty networks (cellular, long replies): we
     * prebuffer ~[PREBUFFER_MS] before starting, and give the track a ~[BUFFER_MS] cushion so it
     * banks audio during fast bursts and rides through stalls. A drain-wait at the end lets that
     * larger buffer finish playing instead of being truncated by stop()/release().
     */
    private fun playPcmStream(stream: java.io.InputStream, sampleRate: Int, channels: Int) {
        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val bytesPerFrame = 2 * channels
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = maxOf(minBuf * 4, sampleRate * bytesPerFrame * BUFFER_MS / 1000)
        val at = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channelMask).build())
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        track = at

        val prebufferFrames = (sampleRate.toLong() * PREBUFFER_MS / 1000)
        var totalFrames = 0L
        var started = false
        fun onWritten(bytes: Int) {
            if (bytes <= 0) return
            totalFrames += bytes / bytesPerFrame
            if (!started && totalFrames >= prebufferFrames) { runCatching { at.play() }; started = true }
        }

        val buf = ByteArray(8192)
        var carry = -1                                   // odd leftover byte between reads
        while (true) {
            val n = try { stream.read(buf) } catch (e: Exception) { -1 }
            if (n < 0) break
            if (n == 0) continue
            var off = 0; var remaining = n
            if (carry >= 0) {                            // complete the split sample first
                onWritten(runCatching { at.write(byteArrayOf(carry.toByte(), buf[0]), 0, 2) }.getOrDefault(0))
                carry = -1; off = 1; remaining = n - 1
            }
            val even = remaining and 0x7FFFFFFE          // whole samples only
            if (even > 0) onWritten(runCatching { at.write(buf, off, even) }.getOrDefault(0))
            if (remaining and 1 == 1) carry = buf[off + even].toInt() and 0xff
        }
        if (!started) runCatching { at.play() }          // clip shorter than the prebuffer

        // Drain: wait until playback catches up to everything written (or is externally stopped).
        runCatching {
            while (true) {
                val head = at.playbackHeadPosition.toLong() and 0xffffffffL
                if (head >= totalFrames) break
                Thread.sleep(60)
                val now = at.playbackHeadPosition.toLong() and 0xffffffffL
                if (now == head) break                   // stalled at end or stop()/flush() from stop()
            }
        }
        runCatching { at.stop() }; runCatching { at.release() }; track = null
    }

    private fun applyVoice() {
        val want = cfg.ttsVoice
        if (want.isNotBlank())
            runCatching { tts?.voices?.firstOrNull { it.name.equals(want, true) }?.let { tts?.voice = it } }
    }

    /** Names of available system voices (for the settings picker / logging). */
    fun voiceNames(): List<String> =
        runCatching { tts?.voices?.map { "${it.name} (${it.locale})" }?.sorted() ?: emptyList() }.getOrDefault(emptyList())

    private fun sysSpeak(text: String) {
        android.util.Log.i("ClawPTT", "TTS speak voice=${tts?.voice?.name}")
        val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "clawptt") ?: TextToSpeech.ERROR
        if (r == TextToSpeech.ERROR) {          // binding dropped — rebind and retry
            pending = text
            runCatching { tts?.shutdown() }; tts = null; ready = false
            init()
        }
    }

    private fun speakEleven(text: String) = playFile(download(elevenReq(text), File(ctx.cacheDir, "reply.mp3")))

    private fun buildReq(url: String, bearer: String?, extra: Map<String, String>, json: String): Request {
        val b = Request.Builder().url(url).addHeader("Content-Type", "application/json")
        if (bearer != null) b.addHeader("Authorization", "Bearer $bearer")
        extra.forEach { (k, v) -> b.addHeader(k, v) }
        return b.post(json.toRequestBody("application/json".toMediaType())).build()
    }

    private fun download(req: Request, out: File): File {
        val t0 = System.currentTimeMillis()
        http.newCall(req).execute().use { resp ->
            android.util.Log.i("ClawPTT", "TTS ${req.url} -> HTTP ${resp.code} in ${System.currentTimeMillis() - t0}ms")
            if (!resp.isSuccessful) throw RuntimeException("TTS HTTP ${resp.code}: ${resp.body?.string()?.take(160)}")
            out.outputStream().use { resp.body!!.byteStream().copyTo(it) }
        }
        android.util.Log.i("ClawPTT", "TTS total (with download) ${System.currentTimeMillis() - t0}ms, ${out.length()} bytes")
        return out
    }

    private fun elevenReq(text: String) = buildReq(
        "https://api.elevenlabs.io/v1/text-to-speech/${cfg.elevenVoiceId}", null,
        mapOf("xi-api-key" to cfg.elevenKey, "Accept" to "audio/mpeg"),
        JSONObject().put("text", text).put("model_id", "eleven_turbo_v2_5").toString())

    private fun veniceReq(text: String) = buildReq(
        "https://api.venice.ai/api/v1/audio/speech", cfg.veniceKey, emptyMap(),
        JSONObject().put("input", text).put("model", cfg.veniceModel).put("voice", cfg.veniceVoice)
            .put("response_format", "mp3").put("streaming", false).toString())

    private fun kokoroWavReq(text: String) = buildReq(
        "${cfg.ttsServiceUrl}/v1/audio/speech", cfg.ttsServiceToken, emptyMap(),
        JSONObject().put("model", "kokoro").put("input", text).put("voice", cfg.kokoroVoice)
            .put("response_format", "wav").put("stream", false).toString())

    /** Fetch the reply audio to a cache file (network modes only). Blocking; call off the main thread. */
    fun fetchToCache(text: String): File? = runCatching {
        val out = File(ctx.cacheDir, "pregen.audio")
        when (cfg.ttsMode) {
            Config.TtsMode.KOKORO -> download(kokoroWavReq(text), out)
            Config.TtsMode.ELEVENLABS -> download(elevenReq(text), out)
            Config.TtsMode.VENICE -> download(veniceReq(text), out)
            else -> null
        }
    }.getOrNull()

    /** Play a pre-fetched audio file; onDone fires at completion. */
    fun playCached(f: File) = playFile(f)

    private fun playFile(f: File) {
        stop()
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            setOnCompletionListener { onDone?.invoke() }
            prepare(); start()
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
        runCatching { kokoroCall?.cancel() }
        track?.let { runCatching { it.pause(); it.flush(); it.stop(); it.release() } }
        track = null
    }

    fun shutdown() { stop(); tts?.shutdown(); tts = null }
}
