package com.clawptt

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted, on-device settings. Secrets (gateway token, API keys) never leave the device
 * and are stored via EncryptedSharedPreferences.
 */
class Config(context: Context) {

    enum class SttMode { VOSK, GATEWAY, OPENAI_WHISPER, SPEECH_RECOGNIZER, VENICE }
    enum class TtsMode { SYSTEM, ELEVENLABS, KOKORO, VENICE }

    private val prefs = run {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "clawptt_secure_prefs",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // Gateway (OpenClaw, behind Tailscale Serve): base URL like https://host.tailnet.ts.net
    var baseUrl: String
        get() = prefs.getString("baseUrl", "")!!.trimEnd('/')
        set(v) = prefs.edit().putString("baseUrl", v).apply()

    var token: String
        get() = prefs.getString("token", "")!!
        set(v) = prefs.edit().putString("token", v).apply()

    // OpenClaw agent target (NOT a provider model name).
    var model: String
        get() = prefs.getString("model", "openclaw/default")!!
        set(v) = prefs.edit().putString("model", v).apply()

    // Optional underlying provider model, sent as x-openclaw-model header. Blank = gateway default.
    var underlyingModel: String
        get() = prefs.getString("underlyingModel", "")!!
        set(v) = prefs.edit().putString("underlyingModel", v).apply()

    var systemPrompt: String
        get() = prefs.getString("systemPrompt", "You are a helpful voice assistant. Keep replies concise and speakable.")!!
        set(v) = prefs.edit().putString("systemPrompt", v).apply()

    var sttMode: SttMode
        get() = SttMode.valueOf(prefs.getString("sttMode", SttMode.VOSK.name)!!)
        set(v) = prefs.edit().putString("sttMode", v.name).apply()

    var ttsMode: TtsMode
        get() = TtsMode.valueOf(prefs.getString("ttsMode", TtsMode.SYSTEM.name)!!)
        set(v) = prefs.edit().putString("ttsMode", v.name).apply()

    // Exact system TTS voice name (blank = engine default, i.e. whatever SherpaTTS is set to)
    var ttsVoice: String
        get() = prefs.getString("ttsVoice", "")!!
        set(v) = prefs.edit().putString("ttsVoice", v).apply()

    // OpenAI Whisper fallback (only used if sttMode == OPENAI_WHISPER)
    var openaiKey: String
        get() = prefs.getString("openaiKey", "")!!
        set(v) = prefs.edit().putString("openaiKey", v).apply()

    // Dedicated self-hosted Whisper STT microservice (sttMode == GATEWAY).
    // Separate from the chat gateway. Blank falls back to the chat baseUrl/token.
    var sttBaseUrl: String
        get() = prefs.getString("sttBaseUrl", "")!!.trimEnd('/')
        set(v) = prefs.edit().putString("sttBaseUrl", v).apply()

    var sttToken: String
        get() = prefs.getString("sttToken", "")!!
        set(v) = prefs.edit().putString("sttToken", v).apply()

    // ElevenLabs (only used if ttsMode == ELEVENLABS)
    var elevenKey: String
        get() = prefs.getString("elevenKey", "")!!
        set(v) = prefs.edit().putString("elevenKey", v).apply()

    var elevenVoiceId: String
        get() = prefs.getString("elevenVoiceId", "21m00Tcm4TlvDq8ikWAM")!! // "Rachel" default
        set(v) = prefs.edit().putString("elevenVoiceId", v).apply()

    // Self-hosted Kokoro TTS service (OpenAI-compatible /v1/audio/speech, streaming PCM)
    var ttsServiceUrl: String
        get() = prefs.getString("ttsServiceUrl", "")!!.trimEnd('/')
        set(v) = prefs.edit().putString("ttsServiceUrl", v).apply()

    var ttsServiceToken: String
        get() = prefs.getString("ttsServiceToken", "")!!
        set(v) = prefs.edit().putString("ttsServiceToken", v).apply()

    var kokoroVoice: String
        get() = prefs.getString("kokoroVoice", "af_bella")!!
        set(v) = prefs.edit().putString("kokoroVoice", v).apply()

    // Venice.ai TTS (https://api.venice.ai/api/v1/audio/speech)
    var veniceKey: String
        get() = prefs.getString("veniceKey", "")!!
        set(v) = prefs.edit().putString("veniceKey", v).apply()

    var veniceModel: String
        get() = prefs.getString("veniceModel", "tts-kokoro")!!
        set(v) = prefs.edit().putString("veniceModel", v).apply()

    var veniceVoice: String
        get() = prefs.getString("veniceVoice", "af_sky")!!
        set(v) = prefs.edit().putString("veniceVoice", v).apply()

    // Venice.ai STT model (https://api.venice.ai/api/v1/audio/transcriptions), reuses veniceKey
    var veniceSttModel: String
        get() = prefs.getString("veniceSttModel", "fal-ai/wizper")!!
        set(v) = prefs.edit().putString("veniceSttModel", v).apply()

    // Pre-generate + cache the reply audio in the background when "Speak replies aloud" is off
    var preGenAudio: Boolean
        get() = prefs.getBoolean("preGenAudio", false)
        set(v) = prefs.edit().putBoolean("preGenAudio", v).apply()

    // On metered/cellular, buffer the whole TTS clip before playing (smoother; higher latency).
    // Off = always stream (low latency, but can be choppy on mobile data).
    var bufferOnCellular: Boolean
        get() = prefs.getBoolean("bufferOnCellular", true)
        set(v) = prefs.edit().putBoolean("bufferOnCellular", v).apply()

    // Behavior toggles
    var speakAloud: Boolean
        get() = prefs.getBoolean("speakAloud", true)
        set(v) = prefs.edit().putBoolean("speakAloud", v).apply()

    var ongoingNotification: Boolean
        get() = prefs.getBoolean("ongoingNotification", true)
        set(v) = prefs.edit().putBoolean("ongoingNotification", v).apply()

    var autoReconnect: Boolean
        get() = prefs.getBoolean("autoReconnect", true)
        set(v) = prefs.edit().putBoolean("autoReconnect", v).apply()

    var showOverLock: Boolean
        get() = prefs.getBoolean("showOverLock", true)
        set(v) = prefs.edit().putBoolean("showOverLock", v).apply()

    // Power saver: cut all radios (airplane) after the screen's off, restore on wake/PTT.
    var powerSaverEnabled: Boolean
        get() = prefs.getBoolean("powerSaverEnabled", false)
        set(v) = prefs.edit().putBoolean("powerSaverEnabled", v).apply()

    var airplaneIdleMinutes: Int
        get() = prefs.getInt("airplaneIdleMinutes", 5)
        set(v) = prefs.edit().putInt("airplaneIdleMinutes", v).apply()

    // Internal: true while airplane mode was turned on BY us (so we only undo our own).
    var airplaneSetByUs: Boolean
        get() = prefs.getBoolean("airplaneSetByUs", false)
        set(v) = prefs.edit().putBoolean("airplaneSetByUs", v).apply()

    val isConfigured: Boolean get() = baseUrl.isNotEmpty() && token.isNotEmpty()

    /**
     * Stable OpenClaw session key ("rabbit-r1:<uuid>") sent as the OpenAI `user` field so the
     * gateway threads all turns into ONE conversation. Reset it to start a fresh thread.
     */
    val sessionKey: String
        get() {
            var s = prefs.getString("sessionKey", "")!!
            if (s.isEmpty()) { s = "rabbit-r1:${java.util.UUID.randomUUID()}"; prefs.edit().putString("sessionKey", s).apply() }
            return s
        }

    fun newConversation() {
        prefs.edit().putString("sessionKey", "rabbit-r1:${java.util.UUID.randomUUID()}").apply()
    }
}
