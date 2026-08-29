package com.clawptt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.ZipInputStream

/** Carded openclaw settings. Sage = STT/input, terracotta = TTS/output. */
class MainActivity : AppCompatActivity() {

    private lateinit var cfg: Config
    private val C = { id: Int -> Ui.col(this, id) }
    private fun dp(v: Float) = Ui.dp(this, v)
    private val revealed = mutableSetOf<String>()

    private lateinit var content: LinearLayout
    private lateinit var connDot: View
    private lateinit var connText: TextView
    private var voskStatus: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)

        // Match the system bars to the app background instead of the Material3 light-theme purple.
        window.statusBarColor = C(R.color.oc_bg)
        window.navigationBarColor = C(R.color.oc_bg)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false   // light icons on the dark bar

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C(R.color.oc_bg))
        }
        root.addView(header())
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26f), 0, dp(26f), dp(20f))
        }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(footer())
        setContentView(root)
        render()
    }

    override fun onResume() { super.onResume(); render() }

    // ---------- header / footer ----------
    private fun header(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(26f), dp(24f), dp(26f), dp(16f))
        }
        row.addView(ImageView(this).apply { setImageResource(R.drawable.mascot); scaleType = ImageView.ScaleType.FIT_CENTER },
            LinearLayout.LayoutParams(dp(52f), dp(52f)).apply { rightMargin = dp(12f) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = "openclaw"; typeface = Ui.figtreeBold(this@MainActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f); setTextColor(C(R.color.oc_accent_pale))
        })
        val cr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        connDot = View(this).apply { background = Ui.pill(C(R.color.oc_text_quaternary)) }
        connText = TextView(this).apply {
            typeface = Ui.figtreeSemibold(this@MainActivity); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(C(R.color.oc_text_tertiary))
            text = if (cfg.baseUrl.isBlank()) "Not configured" else host(cfg.baseUrl)
        }
        cr.addView(connDot, LinearLayout.LayoutParams(dp(9f), dp(9f)).apply { rightMargin = dp(7f) })
        cr.addView(connText)
        col.addView(cr)
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))   // takes the slack, pushes button right
        row.addView(btn("Test", C(R.color.oc_accent), C(R.color.oc_on_accent)) { testConnection() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(16f), dp(9f), dp(16f), dp(9f))
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(10f) })
        return row
    }

    private fun footer(): View {
        val f = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26f), dp(8f), dp(26f), dp(14f))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8f), 1) }
        row.addView(btn("Chat", 0, C(R.color.oc_text_secondary), C(R.color.oc_stroke)) {
            startActivity(Intent(this, ConversationActivity::class.java))
        }, LinearLayout.LayoutParams(0, dp(48f), 1f))
        row.addView(spacer())
        row.addView(btn("History", 0, C(R.color.oc_text_secondary), C(R.color.oc_stroke)) {
            startActivity(Intent(this, HistoryActivity::class.java))
        }, LinearLayout.LayoutParams(0, dp(48f), 1f))
        row.addView(spacer())
        row.addView(btn("New chat", 0, C(R.color.oc_text_secondary), C(R.color.oc_stroke)) {
            cfg.newConversation()      // resets the OpenClaw session key -> fresh thread
            Conversation.clear(this)   // and clears the persisted chat-page thread
            startService(Intent(this, PttService::class.java).setAction(PttService.ACTION_RESET))  // next hold = one-off panel
            Toast.makeText(this, "Started a fresh conversation", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, dp(48f), 1f))
        f.addView(row)
        return f
    }

    // ---------- render body ----------
    private fun render() {
        content.removeAllViews()

        sectionHeader("Gateway")
        content.addView(valueRow("openclaw host", cfg.baseUrl.ifBlank { "Not set" }, mono = true) {
            edit("openclaw host", cfg.baseUrl, false) { cfg.baseUrl = it.trimEnd('/'); render() }
        })
        content.addView(gap(10))
        content.addView(maskedRow("Gateway token", cfg.token, "gwTok") {
            edit("Gateway token", cfg.token, true) { cfg.token = it; render() }
        })
        content.addView(gap(10))
        content.addView(toggleRow("Auto-reconnect", "Retry on wake", cfg.autoReconnect) { cfg.autoReconnect = it })

        sectionHeader("Speech to text")
        content.addView(sttPicker())
        content.addView(hint(sttDesc()))
        sttFields()

        sectionHeader("Text to speech")
        content.addView(ttsPicker())
        content.addView(hint(ttsDesc()))
        ttsFields()

        sectionHeader("Behavior")
        content.addView(toggleRow("Show over lock screen", "Panel wakes the display", cfg.showOverLock) { cfg.showOverLock = it })
        content.addView(gap(10))
        content.addView(toggleRow("Speak replies aloud", "Off shows text only", cfg.speakAloud) { cfg.speakAloud = it })
        content.addView(gap(10))
        content.addView(toggleRow("Pre-generate audio", "Text-only: ready the play button in the background", cfg.preGenAudio) { cfg.preGenAudio = it })
        content.addView(gap(10))
        content.addView(toggleRow("Buffer on cellular", "Smoother network TTS on mobile data (waits for the full clip)", cfg.bufferOnCellular) { cfg.bufferOnCellular = it })
        content.addView(gap(10))
        content.addView(toggleRow("Ongoing notification", "Keeps the service alive", cfg.ongoingNotification) { cfg.ongoingNotification = it })

        sectionHeader("Power saver")
        content.addView(toggleRow("Airplane when idle",
            "Cuts all radios after the screen's off; back on when you wake it or press PTT. Needs root.",
            cfg.powerSaverEnabled) {
            cfg.powerSaverEnabled = it
            if (!it) AirplaneManager.disengage(this)   // never leave the user stranded offline
        })
        content.addView(gap(10))
        content.addView(valueRow("Idle timeout", "${cfg.airplaneIdleMinutes} min") {
            edit("Minutes idle before airplane", cfg.airplaneIdleMinutes.toString(), false) { v ->
                cfg.airplaneIdleMinutes = v.trim().toIntOrNull()?.coerceIn(1, 240) ?: cfg.airplaneIdleMinutes
                render()
            }
        })
        content.addView(gap(10))
        content.addView(btn("Test root access", 0, C(R.color.oc_text_secondary), C(R.color.oc_stroke)) {
            Toast.makeText(this, "Testing… approve the Magisk prompt", Toast.LENGTH_SHORT).show()
            AirplaneManager.test(this) { ok ->
                runOnUiThread {
                    Toast.makeText(this,
                        if (ok) "Root OK — airplane control ready" else "Root denied — grant ClawPTT in Magisk",
                        Toast.LENGTH_LONG).show()
                }
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48f)))

        content.addView(buildLine())
    }

    // ---------- STT ----------
    private fun sttPicker(): View {
        val items = listOf("Self-host" to Config.SttMode.GATEWAY, "whisperIME" to Config.SttMode.SPEECH_RECOGNIZER,
            "Vosk" to Config.SttMode.VOSK, "OpenAI" to Config.SttMode.OPENAI_WHISPER,
            "Venice" to Config.SttMode.VENICE)
        return gridPicker(items.map { it.first }, 2, items.indexOfFirst { it.second == cfg.sttMode },
            C(R.color.oc_sage_light), C(R.color.oc_on_sage)) { i -> cfg.sttMode = items[i].second; render() }
    }

    private fun sttDesc() = when (cfg.sttMode) {
        Config.SttMode.GATEWAY -> "faster-whisper on your VPS over Tailscale. Accurate and fast."
        Config.SttMode.SPEECH_RECOGNIZER -> "On-device Whisper via whisperIME. Accurate, slower."
        Config.SttMode.VOSK -> "Fully offline and fast. Lower accuracy than Whisper."
        Config.SttMode.OPENAI_WHISPER -> "Cloud transcription. Needs an API key."
        Config.SttMode.VENICE -> "Venice.ai cloud transcription (fal-ai/wizper). Fast, cheap, needs an API key."
    }

    private fun sttFields() {
        val accent = C(R.color.oc_sage)
        when (cfg.sttMode) {
            Config.SttMode.GATEWAY -> {
                groupHeader("Self-host · setup", accent)
                content.addView(fieldCard(accent, valueRow("Service URL", cfg.sttBaseUrl.ifBlank { "Not set" }, mono = true) {
                    edit("Service URL", cfg.sttBaseUrl, false) { cfg.sttBaseUrl = it.trimEnd('/'); render() }
                }))
                content.addView(gap(10))
                content.addView(fieldCard(accent, maskedRow("Service token", cfg.sttToken, "sttTok") {
                    edit("Service token", cfg.sttToken, true) { cfg.sttToken = it; render() }
                }))
            }
            Config.SttMode.SPEECH_RECOGNIZER -> {
                groupHeader("whisperIME · setup", accent)
                val installed = isWhisperImeInstalled()
                content.addView(fieldCard(accent, statusRow(if (installed) "whisperIME installed" else "Not installed",
                    if (installed) "Ready" else "Missing", installed) {
                    if (!installed) openMarket("com.woheller69.whisper")
                }))
            }
            Config.SttMode.VOSK -> { groupHeader("Vosk · setup", accent); content.addView(voskCard(accent)) }
            Config.SttMode.OPENAI_WHISPER -> {
                groupHeader("OpenAI · setup", accent)
                content.addView(fieldCard(accent, maskedRow("API key", cfg.openaiKey, "oaKey") {
                    edit("OpenAI API key", cfg.openaiKey, true) { cfg.openaiKey = it; render() }
                }))
            }
            Config.SttMode.VENICE -> {
                groupHeader("Venice · setup", accent)
                content.addView(fieldCard(accent, maskedRow("API key", cfg.veniceKey, "venSttKey") {
                    edit("Venice API key", cfg.veniceKey, true) { cfg.veniceKey = it; render() }
                }))
                content.addView(gap(10))
                content.addView(fieldCard(accent, valueRow("Model", cfg.veniceSttModel, mono = true) {
                    edit("Venice STT model", cfg.veniceSttModel, false) { cfg.veniceSttModel = it; render() }
                }))
            }
        }
    }

    // ---------- TTS ----------
    private fun ttsPicker(): View {
        val items = listOf("Sherpa" to Config.TtsMode.SYSTEM, "Kokoro" to Config.TtsMode.KOKORO,
            "Venice" to Config.TtsMode.VENICE, "Eleven" to Config.TtsMode.ELEVENLABS)
        return grid2x2(items.map { it.first }, items.indexOfFirst { it.second == cfg.ttsMode },
            C(R.color.oc_accent), C(R.color.oc_on_accent)) { i -> cfg.ttsMode = items[i].second; render() }
    }

    private fun ttsDesc() = when (cfg.ttsMode) {
        Config.TtsMode.ELEVENLABS -> "Cloud voice. Needs an API key."
        Config.TtsMode.SYSTEM -> "Android system engine (Sherpa). Works offline."
        Config.TtsMode.KOKORO -> "Self-hosted Kokoro on your VPS. Streamed, gapless."
        Config.TtsMode.VENICE -> "Venice.ai cloud TTS (incl. Kokoro). Fast, needs an API key."
    }

    private fun ttsFields() {
        val accent = C(R.color.oc_accent)
        when (cfg.ttsMode) {
            Config.TtsMode.ELEVENLABS -> {
                groupHeader("ElevenLabs · setup", accent)
                content.addView(fieldCard(accent, maskedRow("API key", cfg.elevenKey, "elKey") {
                    edit("ElevenLabs API key", cfg.elevenKey, true) { cfg.elevenKey = it; render() }
                }))
                content.addView(gap(10))
                content.addView(fieldCard(accent, valueRow("Voice ID", cfg.elevenVoiceId, mono = true) {
                    edit("Voice ID", cfg.elevenVoiceId, false) { cfg.elevenVoiceId = it; render() }
                }))
            }
            Config.TtsMode.SYSTEM -> {
                groupHeader("Sherpa · setup", accent)
                content.addView(fieldCard(accent, valueRow("Voice name", cfg.ttsVoice.ifBlank { "System default" }) {
                    edit("System TTS voice name", cfg.ttsVoice, false) { cfg.ttsVoice = it.trim(); render() }
                }))
            }
            Config.TtsMode.KOKORO -> {
                groupHeader("Kokoro · setup", accent)
                content.addView(fieldCard(accent, valueRow("Service URL", cfg.ttsServiceUrl.ifBlank { "Not set" }, mono = true) {
                    edit("Kokoro service URL", cfg.ttsServiceUrl, false) { cfg.ttsServiceUrl = it.trimEnd('/'); render() }
                }))
                content.addView(gap(10))
                content.addView(fieldCard(accent, maskedRow("Service token", cfg.ttsServiceToken, "ttsTok") {
                    edit("Kokoro service token", cfg.ttsServiceToken, true) { cfg.ttsServiceToken = it; render() }
                }))
                content.addView(gap(10))
                content.addView(fieldCard(accent, valueRow("Voice", cfg.kokoroVoice) {
                    edit("Kokoro voice", cfg.kokoroVoice, false) { cfg.kokoroVoice = it.trim().ifBlank { "af_bella" }; render() }
                }))
            }
            Config.TtsMode.VENICE -> {
                groupHeader("Venice · setup", accent)
                content.addView(fieldCard(accent, maskedRow("API key", cfg.veniceKey, "venKey") {
                    edit("Venice API key", cfg.veniceKey, true) { cfg.veniceKey = it; VeniceCatalog.clear(); render() }
                }))
                content.addView(gap(10))
                content.addView(fieldCard(accent, valueRow("Model", cfg.veniceModel) { pickVeniceModel() }))
                content.addView(gap(10))
                content.addView(fieldCard(accent, valueRow("Voice", cfg.veniceVoice) { pickVeniceVoice() }))
            }
        }
    }

    // ---------- Venice model / voice pickers (live catalog from the API) ----------
    private fun pickVeniceModel() {
        if (cfg.veniceKey.isBlank()) { Toast.makeText(this, "Enter your Venice API key first", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, "Loading Venice models…", Toast.LENGTH_SHORT).show()
        VeniceCatalog.load(cfg.veniceKey) { models ->
            if (models.isNullOrEmpty()) {   // fetch failed — fall back to manual entry
                edit("Venice model (e.g. tts-kokoro)", cfg.veniceModel, false) { cfg.veniceModel = it.trim().ifBlank { "tts-kokoro" }; render() }
                return@load
            }
            pickFromList("Model", models.map { it.id }, cfg.veniceModel) { picked ->
                cfg.veniceModel = picked
                val m = models.first { it.id == picked }
                if (cfg.veniceVoice !in m.voices) cfg.veniceVoice = m.defaultVoice   // keep the voice valid for the model
                render()
            }
        }
    }

    private fun pickVeniceVoice() {
        if (cfg.veniceKey.isBlank()) { Toast.makeText(this, "Enter your Venice API key first", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, "Loading voices…", Toast.LENGTH_SHORT).show()
        VeniceCatalog.load(cfg.veniceKey) { models ->
            val m = models?.firstOrNull { it.id == cfg.veniceModel }
            if (m == null || m.voices.isEmpty()) {
                edit("Venice voice", cfg.veniceVoice, false) { cfg.veniceVoice = it.trim(); render() }
                return@load
            }
            pickFromList("Voice · ${m.id}", m.voices, cfg.veniceVoice) { cfg.veniceVoice = it; render() }
        }
    }

    private fun pickFromList(title: String, items: List<String>, current: String, onPick: (String) -> Unit) {
        val arr = items.toTypedArray()
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setSingleChoiceItems(arr, items.indexOf(current)) { d, i -> onPick(arr[i]); d.dismiss() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Vosk card ----------
    private fun voskCard(accent: Int): View {
        val ready = SttEngine.isVoskModelReady(this)
        val card = cardBase()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(body("Model", C(R.color.oc_text), 12.5f))
        left.addView(caption("vosk-model-small-en-us · 40 MB", C(R.color.oc_text_quaternary)))
        top.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val chipText = if (ready) "Ready" else voskStatus.ifBlank { "Missing" }
        top.addView(chip(chipText, if (ready) C(R.color.oc_sage_dark) else C(R.color.oc_accent_dark),
            if (ready) C(R.color.oc_sage_pale) else C(R.color.oc_accent_light)))
        card.addView(top)
        card.addView(gap(12))
        card.addView(btn(if (ready) "Re-download model" else "Download model", 0, C(R.color.oc_text_secondary), C(R.color.oc_stroke)) {
            downloadVosk()
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(50f)))
        return fieldCard(accent, card)
    }

    // ---------- building blocks ----------
    private fun sectionHeader(t: String) = content.addView(TextView(this).apply {
        text = t; isAllCaps = true; letterSpacing = 0.16f
        typeface = Ui.figtreeSemibold(this@MainActivity); setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
        setTextColor(C(R.color.oc_text_quaternary)); setPadding(dp(2f), dp(20f), 0, dp(10f))
    })

    private fun groupHeader(t: String, accent: Int) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2f), dp(14f), 0, dp(9f)) }
        r.addView(View(this).apply { background = Ui.pill(accent) }, LinearLayout.LayoutParams(dp(7f), dp(7f)).apply { rightMargin = dp(8f) })
        r.addView(TextView(this).apply {
            text = t; isAllCaps = true; letterSpacing = 0.16f
            typeface = Ui.figtreeSemibold(this@MainActivity); setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f); setTextColor(accent)
        })
        content.addView(r)
    }

    private fun cardBase() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = Ui.card(this@MainActivity, C(R.color.oc_surface), 10f)
        setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
    }

    private fun valueRow(sub: String, value: String, mono: Boolean = false, onEdit: () -> Unit): View {
        val card = cardBase()
        card.addView(caption(sub, C(R.color.oc_text_tertiary)))
        card.addView(TextView(this).apply {
            text = value
            typeface = if (mono) Typeface.MONOSPACE else Ui.figtree(this@MainActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (mono) 13f else 12.5f)
            setTextColor(if (value == "Not set" || value == "System default") C(R.color.oc_text_quaternary) else C(R.color.oc_text_bright))
            setPadding(0, dp(4f), 0, 0)
        })
        card.isClickable = true; card.setOnClickListener { onEdit() }
        return card
    }

    private fun maskedRow(sub: String, value: String, key: String, onEdit: () -> Unit): View {
        val card = cardBase()
        card.addView(caption(sub, C(R.color.oc_text_tertiary)))
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4f), 0, 0) }
        r.addView(TextView(this).apply {
            text = mask(value, key); typeface = Typeface.MONOSPACE; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (value.isBlank()) C(R.color.oc_text_quaternary) else C(R.color.oc_text_bright))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        r.addView(FrameLayout(this).apply {
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_eye); setColorFilter(C(R.color.oc_text_tertiary)) },
                FrameLayout.LayoutParams(dp(22f), dp(22f)))
            isClickable = true; setOnClickListener { if (key in revealed) revealed.remove(key) else revealed.add(key); render() }
        })
        card.addView(r)
        card.isClickable = true; card.setOnClickListener { onEdit() }
        return card
    }

    private fun statusRow(title: String, chipText: String, ok: Boolean, onClick: () -> Unit): View {
        val card = cardBase()
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(body(title, C(R.color.oc_text), 12.5f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        r.addView(chip(chipText, if (ok) C(R.color.oc_sage_dark) else C(R.color.oc_accent_dark),
            if (ok) C(R.color.oc_sage_pale) else C(R.color.oc_accent_light)))
        card.addView(r)
        if (!ok) { card.isClickable = true; card.setOnClickListener { onClick() } }
        return card
    }

    private fun toggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val card = cardBase()
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(body(title, C(R.color.oc_text), 12.5f)); left.addView(caption(sub, C(R.color.oc_text_tertiary)))
        r.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        r.addView(SwitchCompat(this).apply {
            isChecked = checked
            thumbTintList = stateList(C(R.color.oc_on_accent), C(R.color.oc_text_quaternary))
            trackTintList = stateList(C(R.color.oc_accent), C(R.color.oc_divider))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
        card.addView(r)
        return card
    }

    private fun grid2x2(labels: List<String>, sel: Int, selFill: Int, selText: Int, onSel: (Int) -> Unit): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = Ui.card(this@MainActivity, C(R.color.oc_surface), 12f); setPadding(dp(5f), dp(5f), dp(5f), dp(5f))
        }
        for (rowI in 0..1) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (colI in 0..1) {
                val i = rowI * 2 + colI
                row.addView(segment(labels[i], i == sel, selFill, selText) { onSel(i) }, LinearLayout.LayoutParams(0, dp(46f), 1f).apply {
                    if (colI == 0) rightMargin = dp(5f)
                })
            }
            container.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (rowI == 0) bottomMargin = dp(5f) })
        }
        return container
    }

    /** Grid of `cols` columns and as many rows as needed; a partial last row stretches to fill. */
    private fun gridPicker(labels: List<String>, cols: Int, sel: Int, selFill: Int, selText: Int, onSel: (Int) -> Unit): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = Ui.card(this@MainActivity, C(R.color.oc_surface), 12f); setPadding(dp(5f), dp(5f), dp(5f), dp(5f))
        }
        val rows = (labels.size + cols - 1) / cols
        for (rowI in 0 until rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (colI in 0 until cols) {
                val i = rowI * cols + colI
                if (i >= labels.size) break
                row.addView(segment(labels[i], i == sel, selFill, selText) { onSel(i) },
                    LinearLayout.LayoutParams(0, dp(46f), 1f).apply { if (colI < cols - 1) rightMargin = dp(5f) })
            }
            container.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { if (rowI < rows - 1) bottomMargin = dp(5f) })
        }
        return container
    }

    private fun rowPicker(labels: List<String>, sel: Int, selFill: Int, selText: Int, onSel: (Int) -> Unit): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = Ui.card(this@MainActivity, C(R.color.oc_surface), 999f); setPadding(dp(5f), dp(5f), dp(5f), dp(5f))
        }
        labels.forEachIndexed { i, l ->
            container.addView(segment(l, i == sel, selFill, selText) { onSel(i) }, LinearLayout.LayoutParams(0, dp(46f), 1f).apply {
                if (i == 0) rightMargin = dp(5f)
            })
        }
        return container
    }

    private fun segment(text: String, selected: Boolean, selFill: Int, selText: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; gravity = Gravity.CENTER
        typeface = Ui.figtreeSemibold(this@MainActivity); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(if (selected) selText else C(R.color.oc_text_tertiary))
        background = if (selected) Ui.pill(selFill) else null
        isClickable = true; setOnClickListener { onClick() }
    }

    private fun fieldCard(accent: Int, cardContent: View): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = Ui.card(this@MainActivity, C(R.color.oc_surface), 10f)
            clipToOutline = true
        }
        row.addView(View(this).apply { setBackgroundColor(accent) }, LinearLayout.LayoutParams(dp(3f), MATCH_PARENT))
        (cardContent as? LinearLayout)?.background = null
        row.addView(cardContent, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return row
    }

    private fun buildLine(): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(2f), dp(20f), 0, 0) }
        r.addView(ImageView(this).apply { setImageResource(R.drawable.mascot); alpha = 0.55f; scaleType = ImageView.ScaleType.FIT_CENTER },
            LinearLayout.LayoutParams(dp(30f), dp(30f)).apply { rightMargin = dp(10f) })
        r.addView(caption("openclaw · build 41 · Rabbit R1", C(R.color.oc_text_quaternary)))
        return r
    }

    // primitives
    private fun body(t: String, color: Int, sp: Float) = TextView(this).apply {
        text = t; typeface = Ui.figtree(this@MainActivity); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
    }
    private fun caption(t: String, color: Int) = TextView(this).apply {
        text = t; typeface = Ui.figtree(this@MainActivity); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); setTextColor(color)
    }
    private fun hint(t: String) = TextView(this).apply {
        text = t; typeface = Ui.figtree(this@MainActivity); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        setTextColor(C(R.color.oc_text_quaternary)); setPadding(dp(4f), dp(8f), dp(4f), 0)
    }
    private fun chip(t: String, fill: Int, textColor: Int) = TextView(this).apply {
        text = t; isAllCaps = true; letterSpacing = 0.10f
        typeface = Ui.figtreeSemibold(this@MainActivity); setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
        setTextColor(textColor); background = Ui.pill(fill); setPadding(dp(13f), dp(8f), dp(13f), dp(8f))
    }
    private fun btn(text: String, fill: Int, textCol: Int, stroke: Int = 0, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; gravity = Gravity.CENTER
        typeface = Ui.figtreeSemibold(this@MainActivity); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(textCol)
        background = if (stroke != 0) Ui.pillStroke(this@MainActivity, stroke) else Ui.pill(fill)
        isClickable = true; setOnClickListener { onClick() }
    }
    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(h.toFloat())) }
    private fun tall() = LinearLayout.LayoutParams(MATCH_PARENT, dp(56f))
    private fun stateList(on: Int, off: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(on, off))

    private fun mask(v: String, key: String): String {
        if (v.isBlank()) return "Not set"
        if (key in revealed) return v
        return "••••  ••••  " + (if (v.length >= 4) v.takeLast(4) else v)
    }
    private fun host(url: String) = url.removePrefix("https://").removePrefix("http://").trimEnd('/')

    // ---------- actions ----------
    private fun edit(title: String, current: String, password: Boolean, onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            setSelection(text.length)
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("Save") { _, _ -> onSave(input.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun testConnection() {
        connText.text = "Testing…"; connDot.background = Ui.pill(C(R.color.oc_text_quaternary))
        ensurePermissions()
        Thread {
            val ok = runCatching { GatewayClient(cfg).chat("ping"); true }.getOrDefault(false)
            runOnUiThread {
                connDot.background = Ui.pill(if (ok) C(R.color.oc_sage_light) else C(R.color.oc_accent_deep))
                connText.text = if (ok) "Connected · ${host(cfg.baseUrl)}" else "Offline · check gateway"
            }
        }.start()
    }

    private fun ensurePermissions() {
        val need = mutableListOf<String>()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(this, need.toTypedArray(), 1)
        if (!Settings.canDrawOverlays(this))
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun isWhisperImeInstalled() =
        runCatching { packageManager.getPackageInfo("com.woheller69.whisper", 0); true }.getOrDefault(false)

    private fun openMarket(pkg: String) = runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
    }.getOrElse { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/$pkg"))) }

    private fun downloadVosk() {
        voskStatus = "Downloading"; render()
        Thread {
            try {
                val dir = SttEngine.modelDir(this); dir.deleteRecursively(); dir.mkdirs()
                OkHttpClient().newCall(Request.Builder().url("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                        ZipInputStream(resp.body!!.byteStream()).use { zip ->
                            var e = zip.nextEntry
                            while (e != null) {
                                val rel = e.name.substringAfter('/')
                                if (rel.isNotEmpty()) {
                                    val out = File(dir, rel)
                                    if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
                                }
                                zip.closeEntry(); e = zip.nextEntry
                            }
                        }
                    }
                voskStatus = ""; runOnUiThread { render() }
            } catch (e: Exception) { voskStatus = "Missing"; runOnUiThread { render() } }
        }.start()
    }
}
