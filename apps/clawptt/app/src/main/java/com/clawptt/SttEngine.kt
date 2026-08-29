package com.clawptt

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Records mic audio while the PTT button is held, transcribes on release.
 *  - VOSK: fully on-device (default). Feeds PCM to a Vosk recognizer live.
 *  - GATEWAY / OPENAI_WHISPER: buffers PCM, POSTs a WAV to a Whisper endpoint on release.
 */
class SttEngine(private val ctx: Context, private val cfg: Config) {

    companion object {
        const val SAMPLE_RATE = 16000
        fun modelDir(ctx: Context) = File(ctx.filesDir, "vosk-model")
        fun isVoskModelReady(ctx: Context) = File(modelDir(ctx), "conf/model.conf").exists()
    }

    @Volatile private var recording = false
    private var recordThread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    private var voskModel: Model? = null
    private var recognizer: Recognizer? = null

    // Explicit generous timeouts. Only setting callTimeout left OkHttp's 10s default read/write
    // timeouts in force, which is too short for self-hosted Whisper over cellular (slow audio
    // upload + slow response) — that caused a SocketTimeout "timeout" ~10s in on mobile data.
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    fun start() {
        if (recording) return
        pcm.reset()
        if (cfg.sttMode == Config.SttMode.VOSK) {
            if (!isVoskModelReady(ctx)) throw RuntimeException("Vosk model not installed (Settings → Download model)")
            voskModel = Model(modelDir(ctx).absolutePath)
            recognizer = Recognizer(voskModel, SAMPLE_RATE.toFloat())
        }
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, SAMPLE_RATE) // >= ~0.5s
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        recording = true
        rec.startRecording()
        recordThread = Thread {
            val buf = ByteArray(bufSize)
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    pcm.write(buf, 0, n)
                    recognizer?.acceptWaveForm(buf, n)
                }
            }
            runCatching { rec.stop() }; rec.release()
        }.also { it.start() }
    }

    /** Discards the in-progress recording without transcribing (cancel mid-talk). */
    fun cancel() {
        recording = false
        recordThread?.join(2000); recordThread = null
        runCatching { recognizer?.close() }; runCatching { voskModel?.close() }
        recognizer = null; voskModel = null
        pcm.reset()
    }

    /** Blocking; call off the main thread. */
    fun stopAndTranscribe(): String {
        recording = false
        recordThread?.join(4000); recordThread = null
        return when (cfg.sttMode) {
            Config.SttMode.VOSK -> {
                val res = recognizer?.finalResult ?: "{}"
                recognizer?.close(); voskModel?.close(); recognizer = null; voskModel = null
                JSONObject(res).optString("text", "").trim()
            }
            Config.SttMode.GATEWAY -> {
                val base = cfg.sttBaseUrl.ifBlank { cfg.baseUrl }
                val tok = cfg.sttToken.ifBlank { cfg.token }
                whisper("$base/v1/audio/transcriptions", "Bearer $tok")
            }
            Config.SttMode.OPENAI_WHISPER -> whisper("https://api.openai.com/v1/audio/transcriptions", "Bearer ${cfg.openaiKey}")
            Config.SttMode.VENICE -> whisper("https://api.venice.ai/api/v1/audio/transcriptions", "Bearer ${cfg.veniceKey}", cfg.veniceSttModel)
            Config.SttMode.SPEECH_RECOGNIZER -> ""  // handled by SpeechRecognizerStt, not this path
        }
    }

    private fun whisper(url: String, auth: String, model: String = "whisper-1"): String {
        val wav = pcmToWav(pcm.toByteArray(), SAMPLE_RATE)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder().url(url).addHeader("Authorization", auth).post(body).build()
        http.newCall(req).execute().use { resp ->
            val t = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("STT HTTP ${resp.code}: ${t.take(200)}")
            return JSONObject(t).optString("text", "").trim()
        }
    }

    private fun pcmToWav(data: ByteArray, sr: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun i(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff); out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff) }
        fun s(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        w("RIFF"); i(36 + data.size); w("WAVE"); w("fmt "); i(16); s(1); s(1); i(sr); i(sr * 2); s(2); s(16)
        w("data"); i(data.size); out.write(data)
        return out.toByteArray()
    }
}
