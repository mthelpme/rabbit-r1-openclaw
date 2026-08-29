package com.clawptt

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Dedicated continuous-conversation page. Unlike the transient [PttActivity] panel, this screen
 * stays open and accumulates the dialogue as chat bubbles. It drives turns with the same hardware
 * PTT side key (handled globally by [PttAccessibilityService] -> [PttService]) and consumes the
 * same [PttService.ACTION_STATE] broadcasts to append/stream turns — it just never dismisses.
 *
 * While this page is foreground it tells the service (ATTACH/DETACH) to suppress the pop-up panel.
 * The thread is persisted via [Conversation] so it survives leaving/returning, and "New chat"
 * clears it alongside Config.newConversation().
 */
class ConversationActivity : AppCompatActivity() {

    private val C = { id: Int -> Ui.col(this, id) }
    private fun dp(v: Float) = Ui.dp(this, v)

    private lateinit var cfg: Config
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var statusRow: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var stopBtn: TextView
    private lateinit var input: EditText
    private lateinit var muteIcon: ImageView
    private var currentPhase = ""

    private val bubbleMaxWidth by lazy { (resources.displayMetrics.widthPixels * 0.80f).toInt() }

    // Live-turn state.
    private var turnActive = false
    private var userBubbleAdded = false              // this turn's question bubble is in place
    private var pendingCard: LinearLayout? = null    // assistant bubble being built this turn
    private var pendingText: TextView? = null        // its answer TextView (null while "thinking")
    private val thinkingAnims = ArrayList<ValueAnimator>()
    private var hintView: View? = null               // the empty-state hint, removed on first turn

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i == null) return
            if (i.getBooleanExtra("done", false)) { onTurnAborted(); return }  // clear live state, don't dismiss
            onState(
                i.getStringExtra("phase") ?: "",
                i.getStringExtra("status") ?: "",
                i.getStringExtra("body") ?: "",
                i.getStringExtra("recap") ?: ""
            )
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        cfg = Config(this)
        // Show over the lock screen so a continuation/hand-off lands here without unlocking.
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.statusBarColor = C(R.color.oc_bg)
        window.navigationBarColor = C(R.color.oc_bg)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(C(R.color.oc_bg))
        }

        // ---- header ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), dp(22f), dp(20f), dp(10f))
        }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.mascot); scaleType = ImageView.ScaleType.FIT_CENTER },
            LinearLayout.LayoutParams(dp(30f), dp(30f)).apply { rightMargin = dp(10f) })
        header.addView(TextView(this).apply {
            text = "openclaw"; typeface = Ui.figtreeBold(this@ConversationActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(C(R.color.oc_accent_pale))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(pill("New chat") { newChat() })
        root.addView(header)

        // ---- message list ----
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(6f), dp(16f), dp(14f))
        }
        scroll = ScrollView(this).apply { addView(list); isVerticalScrollBarEnabled = true }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // ---- bottom status strip ----
        statusDot = View(this)
        statusLabel = TextView(this).apply {
            typeface = Ui.figtreeSemibold(this@ConversationActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(C(R.color.oc_text_tertiary))
            ellipsize = android.text.TextUtils.TruncateAt.END; maxLines = 1
        }
        stopBtn = TextView(this).apply {
            text = "Stop"; gravity = Gravity.CENTER
            typeface = Ui.figtreeSemibold(this@ConversationActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f); setTextColor(C(R.color.oc_accent_light))
            background = Ui.pillStroke(this@ConversationActivity, C(R.color.oc_stroke))
            setPadding(dp(16f), dp(7f), dp(16f), dp(7f))
            isClickable = true; setOnClickListener { interruptTurn() }
        }
        statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22f), dp(10f), dp(22f), dp(16f)); visibility = View.GONE
            addView(statusDot, LinearLayout.LayoutParams(dp(9f), dp(9f)).apply { rightMargin = dp(9f) })
            addView(statusLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(stopBtn)
        }
        root.addView(statusRow)

        // ---- bottom input bar: mute toggle · text field · send ----
        muteIcon = ImageView(this)
        val muteBtn = FrameLayout(this).apply {
            background = Ui.pillStroke(this@ConversationActivity, C(R.color.oc_stroke))
            addView(muteIcon, FrameLayout.LayoutParams(dp(20f), dp(20f), Gravity.CENTER))
            isClickable = true; setOnClickListener { toggleMute() }
        }
        input = EditText(this).apply {
            background = Ui.card(this@ConversationActivity, C(R.color.oc_surface), 18f)
            setPadding(dp(15f), dp(9f), dp(15f), dp(9f))
            typeface = Ui.figtree(this@ConversationActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(C(R.color.oc_text)); setHintTextColor(C(R.color.oc_text_quaternary))
            hint = "Message…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1; maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { sendText(); true } else false
            }
        }
        val sendBtn = TextView(this).apply {
            text = "Send"; gravity = Gravity.CENTER
            typeface = Ui.figtreeSemibold(this@ConversationActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(C(R.color.oc_on_accent))
            background = Ui.pill(C(R.color.oc_accent))
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            isClickable = true; setOnClickListener { sendText() }
        }
        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            setPadding(dp(14f), dp(6f), dp(14f), dp(12f))
            addView(muteBtn, LinearLayout.LayoutParams(dp(42f), dp(40f)).apply { rightMargin = dp(8f) })
            addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(sendBtn, LinearLayout.LayoutParams(WRAP_CONTENT, dp(40f)).apply { leftMargin = dp(8f) })
        }
        root.addView(inputBar)
        updateMuteIcon()

        setContentView(root)
        seed()
    }

    // ---- seeding from the persisted thread ----
    private fun seed() {
        list.removeAllViews()
        for (m in Conversation.all(this)) {
            if (m.role == Conversation.ASSISTANT) {
                val card = newAssistantCard()
                card.addView(answerText(m.text))
            } else {
                addUserBubble(m.text)
            }
        }
        hintView = null
        if (list.childCount == 0) {
            hintView = TextView(this).apply {
                text = "Hold the side key and talk — the conversation stays here."
                typeface = Ui.figtree(this@ConversationActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(C(R.color.oc_text_quaternary))
                setPadding(dp(6f), dp(30f), dp(6f), 0)
            }
            list.addView(hintView)
        }
        scrollToBottom()
    }

    // ---- broadcast state machine ----
    private fun onState(phase: String, status: String, body: String, recap: String) {
        currentPhase = phase
        when (phase) {
            "LISTENING" -> setStatus(C(R.color.oc_sage_light), if (body.isNotBlank()) "🎙  ${unquote(body)}" else "Listening…", stop = false)
            "THINKING" -> {
                if (!turnActive) { turnActive = true; userBubbleAdded = false; clearPlaceholderIfAny() }
                // The transcript arrives in `body` on THINKING (e.g. "“hello”"); add it once, above the answer.
                ensureUserBubble(body)
                if (userBubbleAdded && pendingCard == null) {
                    pendingCard = newAssistantCard().also { it.addView(dotsRow()) }; pendingText = null
                }
                setStatus(C(R.color.oc_accent_light), "Thinking…", stop = true)
            }
            "SPEAKING" -> {
                if (turnActive) { ensureUserBubble(recap); showAssistantText(body) }  // recap is the fallback source
                setStatus(C(R.color.oc_accent_light), "Speaking…", stop = true)
            }
            "READ" -> {
                if (turnActive) { ensureUserBubble(recap); showAssistantText(body); finishTurn() }
                clearStatus()
            }
            "MSG" -> {
                if (turnActive) { removePending(); turnActive = false }
                if (status.isNotBlank()) setStatus(C(R.color.oc_accent_deep), status, stop = false)
                statusRow.postDelayed({ if (!turnActive) clearStatus() }, 2200)
            }
        }
        scrollToBottom()
    }

    /** Interrupt the current turn. Thinking → abort the query (real cancel); Speaking → stop audio, keep text. */
    private fun interruptTurn() {
        when (currentPhase) {
            "THINKING" -> {
                send(PttService.ACTION_CANCEL)
                removePending(); turnActive = false; userBubbleAdded = false
                currentPhase = ""; clearStatus()
            }
            "SPEAKING" -> send(PttService.ACTION_MUTE)  // keeps the text; the READ broadcast finalizes the bubble
        }
    }

    /** Add this turn's question bubble exactly once, from whichever source first carries it. */
    private fun ensureUserBubble(source: String) {
        if (userBubbleAdded) return
        val q = unquote(source)
        if (q.isNotEmpty()) { addUserBubble(q); userBubbleAdded = true }
    }

    private fun unquote(s: String) = s.trim().trim('“', '”', '"').trim()

    /** A turn was canceled/aborted (done=true): clear the live indicators but keep the page open. */
    private fun onTurnAborted() {
        if (turnActive) { removePending(); turnActive = false; userBubbleAdded = false }
        currentPhase = ""
        clearStatus()
    }

    /** Convert the pending "thinking" bubble to a text bubble (or update it) with the streamed text. */
    private fun showAssistantText(text: String) {
        val card = pendingCard ?: newAssistantCard().also { pendingCard = it }
        if (pendingText == null) {
            cancelThinking()
            card.removeAllViews()
            pendingText = answerText(text).also { card.addView(it) }
        } else {
            pendingText!!.text = text
        }
    }

    private fun finishTurn() {
        cancelThinking()
        turnActive = false; pendingCard = null; pendingText = null
    }

    private fun removePending() {
        cancelThinking()
        pendingCard?.let { list.removeView(it) }
        pendingCard = null; pendingText = null
    }

    /** Remove the empty-state hint before the first turn. */
    private fun clearPlaceholderIfAny() {
        hintView?.let { list.removeView(it); hintView = null }
    }

    // ---- bubbles ----
    private fun addUserBubble(text: String) {
        val tv = TextView(this).apply {
            this.text = text; typeface = Ui.figtree(this@ConversationActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(C(R.color.oc_transcript))
            setLineSpacing(0f, 1.35f); maxWidth = bubbleMaxWidth
            background = Ui.card(this@ConversationActivity, C(R.color.oc_sage_dark), 14f)
            setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
        }
        list.addView(tv, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.END; topMargin = dp(8f); bottomMargin = dp(2f); leftMargin = dp(40f)
        })
    }

    private fun newAssistantCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.card(this@ConversationActivity, C(R.color.oc_surface), 14f)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        }
        list.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            gravity = Gravity.START; topMargin = dp(8f); bottomMargin = dp(2f); rightMargin = dp(40f)
        })
        return card
    }

    private fun answerText(text: String) = TextView(this).apply {
        this.text = text; typeface = Ui.figtree(this@ConversationActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); setTextColor(C(R.color.oc_text_bright))
        setLineSpacing(0f, 1.4f)
    }

    private fun dotsRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for (i in 0..2) {
            val d = View(this).apply { background = Ui.pill(C(R.color.oc_accent)) }
            row.addView(d, LinearLayout.LayoutParams(dp(9f), dp(9f)).apply { rightMargin = if (i < 2) dp(8f) else 0 })
            ValueAnimator.ofFloat(0.25f, 1f).apply {
                duration = 1100; startDelay = i * 180L; repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE; addUpdateListener { d.alpha = it.animatedValue as Float }
                thinkingAnims.add(this); start()
            }
        }
        return row
    }

    private fun cancelThinking() {
        thinkingAnims.forEach { it.cancel() }; thinkingAnims.clear()
    }

    // ---- status strip ----
    private fun setStatus(dot: Int, text: String, stop: Boolean) {
        statusRow.visibility = View.VISIBLE
        statusDot.background = Ui.pill(dot)
        statusLabel.text = text
        stopBtn.visibility = if (stop) View.VISIBLE else View.GONE
    }
    private fun clearStatus() { statusRow.visibility = View.GONE; currentPhase = "" }

    private fun scrollToBottom() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    // ---- actions ----
    private fun newChat() {
        cfg.newConversation()
        Conversation.clear(this)
        send(PttService.ACTION_RESET)
        cancelThinking()
        turnActive = false; userBubbleAdded = false; pendingCard = null; pendingText = null
        clearStatus()
        seed()
        Toast.makeText(this, "Started a fresh conversation", Toast.LENGTH_SHORT).show()
    }

    private fun pill(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; gravity = Gravity.CENTER; typeface = Ui.figtreeSemibold(this@ConversationActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(C(R.color.oc_text_secondary))
        background = Ui.pillStroke(this@ConversationActivity, C(R.color.oc_stroke))
        setPadding(dp(18f), dp(9f), dp(18f), dp(9f))
        isClickable = true; setOnClickListener { onClick() }
    }

    private fun send(action: String) = startService(Intent(this, PttService::class.java).setAction(action))

    /** Send the typed message as a turn (no STT); the reply streams back like a voice turn. */
    private fun sendText() {
        val t = input.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return
        startService(Intent(this, PttService::class.java).setAction(PttService.ACTION_SEND_TEXT).putExtra("text", t))
        input.setText("")
    }

    /** Toggle the shared "Speak replies aloud" setting; mute = text-only replies. */
    private fun toggleMute() {
        cfg.speakAloud = !cfg.speakAloud
        updateMuteIcon()
        Toast.makeText(this, if (cfg.speakAloud) "Speaking replies aloud" else "Muted — text only", Toast.LENGTH_SHORT).show()
    }

    private fun updateMuteIcon() {
        muteIcon.setImageResource(if (cfg.speakAloud) R.drawable.ic_volume else R.drawable.ic_volume_off)
        muteIcon.setColorFilter(C(if (cfg.speakAloud) R.color.oc_accent_light else R.color.oc_text_tertiary))
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter(PttService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, f)
        send(PttService.ACTION_ATTACH)   // suppress the pop-up panel while this page is up
        updateMuteIcon()                 // reflect the setting if it changed elsewhere
    }

    override fun onPause() {
        super.onPause()
        send(PttService.ACTION_DETACH)
        runCatching { unregisterReceiver(receiver) }
    }

    override fun onDestroy() { super.onDestroy(); cancelThinking() }
}
