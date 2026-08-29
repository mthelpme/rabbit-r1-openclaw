package com.clawptt

import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * openclaw PTT panel. Show-when-locked, keep-screen-on. Status / center / action skeleton.
 * Speaking and Response are one scrollable answer screen (status + button flip).
 */
class PttActivity : Activity() {

    private val C = { id: Int -> Ui.col(this, id) }
    private fun dp(v: Float) = Ui.dp(this, v)
    private fun dpf(v: Float) = Ui.dpf(this, v)
    private val bump = 2f   // global type size bump (sp)

    private lateinit var root: LinearLayout
    private lateinit var statusRow: LinearLayout
    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var statusTimer: TextView
    private lateinit var center: FrameLayout
    private lateinit var actions: LinearLayout
    private lateinit var mascot: ImageView
    private lateinit var glow: View

    private val ui = Handler(Looper.getMainLooper())
    private var breathe: ValueAnimator? = null
    private var listenStart = 0L
    private val timerTick = object : Runnable {
        override fun run() {
            val s = (SystemClock.elapsedRealtime() - listenStart) / 1000
            statusTimer.text = "%d:%02d".format(s / 60, s % 60)
            ui.postDelayed(this, 500)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.getBooleanExtra("done", false) == true) { finish(); return }
            render(i?.getStringExtra("phase") ?: "LISTENING",
                i?.getStringExtra("status") ?: "", i?.getStringExtra("body") ?: "",
                i?.getStringExtra("recap") ?: "")
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28f), dp(26f), dp(28f), dp(28f))
            clipChildren = false
        }
        statusDot = View(this)
        statusLabel = TextView(this).apply {
            typeface = Ui.figtreeSemibold(this@PttActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f + bump); letterSpacing = 0.14f; isAllCaps = true
        }
        statusTimer = TextView(this).apply {
            typeface = Typeface.MONOSPACE; setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f + bump)
            setTextColor(C(R.color.oc_sage_dim)); gravity = Gravity.END; visibility = View.GONE
        }
        statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(statusDot, LinearLayout.LayoutParams(dp(9f), dp(9f)).apply { rightMargin = dp(9f) })
            addView(statusLabel)
            addView(statusTimer, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        center = FrameLayout(this).apply { clipChildren = false }
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }

        glow = View(this)
        mascot = ImageView(this).apply { setImageResource(R.drawable.mascot); scaleType = ImageView.ScaleType.FIT_CENTER }

        root.addView(statusRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(center, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14f) })
        setContentView(root)

        render(intent?.getStringExtra("phase") ?: "IDLE",
            intent?.getStringExtra("status") ?: "", intent?.getStringExtra("body") ?: "", "")
    }

    private fun render(phase: String, status: String, body: String, recap: String) {
        stopBreathe(); ui.removeCallbacks(timerTick); statusTimer.visibility = View.GONE
        center.removeAllViews(); actions.removeAllViews(); detach(mascot); detach(glow)
        when (phase) {
            "LISTENING" -> renderListening(body)
            "THINKING" -> renderThinking(body)
            "SPEAKING" -> renderAnswer(true, body, recap)
            "READ" -> renderAnswer(false, body, recap)
            "MSG" -> renderMessage(status.ifBlank { body })
            else -> renderIdle()
        }
    }

    private fun renderIdle() {
        root.background = Ui.bgGradient(C(R.color.oc_bg_grad_top), C(R.color.oc_bg_grad_bottom))
        setStatus(C(R.color.oc_sage_light), "openclaw ready", C(R.color.oc_text_secondary))
        val block = orbBlock(178, 210, glowCol(R.color.oc_accent, 0.30f), 0.90f, 1f)
        block.addView(spacer(20)); block.addView(display("Hold to talk", C(R.color.oc_accent_pale), 20f))
        block.addView(spacer(10))
        block.addView(bodyText("Press and hold the side key, then speak", C(R.color.oc_text_tertiary), 10.5f, 300, 3))
        center.addView(block, centerParams())
        startBreathe(2600)
        actions.addView(pill("PTT · side key", C(R.color.oc_text_secondary), C(R.color.oc_stroke)) {}, wrapCenter())
    }

    private fun renderListening(partial: String) {
        root.background = Ui.bgGradient(C(R.color.oc_bg_listen_top), C(R.color.oc_bg_listen_mid))
        setStatus(C(R.color.oc_sage_pale), "listening", C(R.color.oc_sage_light))
        listenStart = SystemClock.elapsedRealtime(); statusTimer.visibility = View.VISIBLE; ui.post(timerTick)
        val block = orbBlock(146, 178, glowCol(R.color.oc_sage, 0.32f), 1f, 1f)
        block.addView(spacer(14)); block.addView(waveform())
        if (partial.isNotBlank()) {
            block.addView(spacer(12))
            block.addView(bodyText(partial, C(R.color.oc_transcript), 12f, 340, 2))
        }
        center.addView(block, centerParams())
        startBreathe(2400)
        actions.addView(btn("Cancel", C(R.color.oc_sage_dark), C(R.color.oc_sage_pale)) { send(PttService.ACTION_CANCEL); finish() }, half())
        actions.addView(gap())
        actions.addView(btn("Send", C(R.color.oc_sage_light), C(R.color.oc_on_sage)) { send(PttService.ACTION_PTT_UP) }, half())
    }

    private fun renderThinking(transcript: String) {
        root.background = Ui.bgGradient(C(R.color.oc_bg_grad_top), C(R.color.oc_bg_grad_bottom))
        setStatus(C(R.color.oc_accent_light), "thinking", C(R.color.oc_text_secondary))
        val block = orbBlock(140, 168, glowCol(R.color.oc_accent, 0.20f), 0.45f, 0.78f)
        block.addView(spacer(16)); block.addView(dots()); block.addView(spacer(14))
        block.addView(bodyText("Sent to openclaw", C(R.color.oc_text_tertiary), 11f, 340, 1))
        if (transcript.isNotBlank()) {
            block.addView(spacer(6))
            block.addView(bodyText(transcript.trim('“', '”', '"'), C(R.color.oc_text_quaternary), 10f, 340, 2))
        }
        center.addView(block, centerParams())
        actions.addView(btn("Cancel", 0, C(R.color.oc_text_secondary), C(R.color.oc_stroke)) { send(PttService.ACTION_CANCEL); finish() }, full())
    }

    /** Merged Speaking + Response: one scrollable document. */
    private fun renderAnswer(speaking: Boolean, answer: String, recap: String) {
        root.background = Ui.bgGradient(C(R.color.oc_bg_grad_top), C(R.color.oc_bg_grad_bottom))
        statusRow.visibility = View.GONE
        val doc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val iconRes = if (speaking) R.drawable.ic_volume else R.drawable.ic_check
        val ringColor = if (speaking) C(R.color.oc_accent_dark) else C(R.color.oc_sage_dark)
        val iconTint = if (speaking) C(R.color.oc_accent_light) else C(R.color.oc_sage_pale)
        val ring = FrameLayout(this).apply {
            background = Ui.pill(ringColor)
            addView(iconView(iconRes, iconTint), FrameLayout.LayoutParams(dp(18f), dp(18f), Gravity.CENTER))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ring, LinearLayout.LayoutParams(dp(34f), dp(34f)).apply { rightMargin = dp(11f) })
        header.addView(label(if (speaking) "speaking" else "response", C(R.color.oc_text_secondary)),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        // Top-right: open the continuous chat page (hands off from this one-off panel).
        header.addView(iconBtn(R.drawable.ic_chat, C(R.color.oc_accent_light)) {
            startActivity(Intent(this, ConversationActivity::class.java)); finish()
        }, LinearLayout.LayoutParams(dp(44f), dp(36f)))
        doc.addView(header)

        if (recap.isNotBlank()) {
            doc.addView(spacer(16))
            val q = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            q.addView(iconView(R.drawable.ic_mic, C(R.color.oc_text_quaternary)),
                LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { rightMargin = dp(10f); topMargin = dp(3f) })
            q.addView(bodyText(recap.trim('“', '”', '"'), C(R.color.oc_text_tertiary), 10.5f, 9999, 2).apply { gravity = Gravity.START })
            doc.addView(q)
        }
        doc.addView(spacer(16)); doc.addView(rule()); doc.addView(spacer(16))

        val answerView = bodyText(answer, C(R.color.oc_text_bright), 12.5f, 9999, 999).apply { gravity = Gravity.START }
        doc.addView(ScrollView(this).apply { addView(answerView); isVerticalScrollBarEnabled = true },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        center.addView(doc, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        actions.addView(iconBtn(R.drawable.ic_volume, C(R.color.oc_accent_light)) { send(PttService.ACTION_SPEAK) },
            LinearLayout.LayoutParams(dp(62f), dp(56f)))
        actions.addView(gap())
        if (speaking)
            actions.addView(btn("Stop speaking", C(R.color.oc_accent), C(R.color.oc_on_accent)) { send(PttService.ACTION_MUTE) }, grow())
        else
            actions.addView(btn("Close", C(R.color.oc_accent), C(R.color.oc_on_accent)) { send(PttService.ACTION_DISMISS); finish() }, grow())
    }

    private fun renderMessage(msg: String) {
        root.background = Ui.bgGradient(C(R.color.oc_bg_error_top), C(R.color.oc_bg_error_mid))
        setStatus(C(R.color.oc_accent_deep), "notice", C(R.color.oc_text_tertiary))
        val block = orbBlock(128, 150, glowCol(R.color.oc_accent, 0.0f), 0.20f, 0.68f)
        block.addView(spacer(18)); block.addView(display("Heads up", C(R.color.oc_accent_pale), 17f))
        block.addView(spacer(10)); block.addView(bodyText(msg, C(R.color.oc_text_tertiary), 10.5f, 340, 4))
        center.addView(block, centerParams())
        actions.addView(btn("Close", 0, C(R.color.oc_text_secondary), C(R.color.oc_stroke)) { send(PttService.ACTION_DISMISS); finish() }, full())
    }

    // ---------- blocks ----------
    private fun orbBlock(mascotDp: Int, glowDp: Int, glowColor: Int, sat: Float, bright: Float): LinearLayout {
        statusRow.visibility = View.VISIBLE
        glow.background = Ui.glow(glowColor, dpf(glowDp / 2f))
        mascot.colorFilter = Ui.mascotFilter(sat, bright)
        val orb = FrameLayout(this).apply {
            clipChildren = false; clipToPadding = false
            addView(glow, FrameLayout.LayoutParams(dp(glowDp.toFloat()), dp(glowDp.toFloat()), Gravity.CENTER))
            addView(mascot, FrameLayout.LayoutParams(dp(mascotDp.toFloat()), dp(mascotDp.toFloat()), Gravity.CENTER))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false
            addView(orb, LinearLayout.LayoutParams(dp(mascotDp.toFloat()), dp(mascotDp.toFloat())).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
    }

    private fun waveform(): View {
        val hs = intArrayOf(14, 24, 34, 40, 30, 20, 26, 12)
        val cs = intArrayOf(R.color.oc_sage, R.color.oc_sage, R.color.oc_sage_light, R.color.oc_sage_pale,
            R.color.oc_sage_light, R.color.oc_sage, R.color.oc_sage, R.color.oc_sage_dim)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        hs.forEachIndexed { i, h ->
            val bar = View(this).apply { background = Ui.pill(C(cs[i])) }
            row.addView(bar, LinearLayout.LayoutParams(dp(7f), dp(h.toFloat())).apply { rightMargin = if (i < 7) dp(7f) else 0 })
            ValueAnimator.ofFloat(0.4f, 1f, 0.5f).apply {
                duration = 900 + i * 60L; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
                addUpdateListener { bar.scaleY = it.animatedValue as Float }; start()
            }
        }
        return FrameLayout(this).apply { addView(row, FrameLayout.LayoutParams(WRAP_CONTENT, dp(42f), Gravity.CENTER)) }
    }

    private fun dots(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for (i in 0..2) {
            val d = View(this).apply { background = Ui.pill(C(R.color.oc_accent)) }
            row.addView(d, LinearLayout.LayoutParams(dp(13f), dp(13f)).apply { rightMargin = if (i < 2) dp(12f) else 0 })
            ValueAnimator.ofFloat(0.25f, 1f).apply {
                duration = 1100; startDelay = i * 180L; repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE; addUpdateListener { d.alpha = it.animatedValue as Float }; start()
            }
        }
        return row
    }

    // ---------- primitives ----------
    private fun setStatus(dot: Int, text: String, textColor: Int) {
        statusRow.visibility = View.VISIBLE; statusDot.background = Ui.pill(dot)
        statusLabel.text = text; statusLabel.setTextColor(textColor)
    }

    private fun display(text: String, color: Int, sp: Float) = TextView(this).apply {
        this.text = text; typeface = Ui.figtreeBold(this@PttActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp + bump); setTextColor(color); gravity = Gravity.CENTER
    }

    private fun label(text: String, color: Int) = TextView(this).apply {
        this.text = text; typeface = Ui.figtreeSemibold(this@PttActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f + bump); letterSpacing = 0.14f; isAllCaps = true; setTextColor(color)
    }

    private fun bodyText(text: String, color: Int, sp: Float, maxWidthDp: Int, maxLines: Int) = TextView(this).apply {
        this.text = text; typeface = Ui.figtree(this@PttActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp + bump); setTextColor(color); gravity = Gravity.CENTER
        setLineSpacing(0f, 1.4f)
        if (maxWidthDp < 9999) maxWidth = dp(maxWidthDp.toFloat())
        if (maxLines < 999) { this.maxLines = maxLines; ellipsize = TextUtils.TruncateAt.END }
    }

    private fun btn(text: String, fill: Int, textCol: Int, stroke: Int = 0, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; gravity = Gravity.CENTER
        typeface = Ui.figtreeSemibold(this@PttActivity); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f + bump); setTextColor(textCol)
        background = if (stroke != 0) Ui.pillStroke(this@PttActivity, stroke) else Ui.pill(fill)
        isClickable = true; setOnClickListener { onClick() }
    }

    private fun pill(text: String, textCol: Int, stroke: Int, onClick: () -> Unit) =
        btn(text, 0, textCol, stroke, onClick).apply {
            setPadding(dp(26f), dp(13f), dp(26f), dp(13f)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f + bump)
        }

    private fun iconBtn(res: Int, tint: Int, onClick: () -> Unit) = FrameLayout(this).apply {
        background = Ui.pillStroke(this@PttActivity, C(R.color.oc_stroke))
        addView(iconView(res, tint), FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER))
        isClickable = true; setOnClickListener { onClick() }
    }

    private fun iconView(res: Int, tint: Int) = ImageView(this).apply { setImageResource(res); setColorFilter(tint) }
    private fun rule() = View(this).apply { setBackgroundColor(C(R.color.oc_divider)); layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1) }
    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(h.toFloat())) }
    private fun gap() = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(14f), 1) }

    private fun centerParams() = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER)
    private fun wrapCenter() = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER }
    private fun half() = LinearLayout.LayoutParams(0, dp(56f), 1f)
    private fun grow() = LinearLayout.LayoutParams(0, dp(56f), 1f)
    private fun full() = LinearLayout.LayoutParams(MATCH_PARENT, dp(56f))

    private fun startBreathe(period: Long) {
        breathe = ValueAnimator.ofFloat(1f, 1.035f).apply {
            duration = period / 2; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
            addUpdateListener { val s = it.animatedValue as Float; mascot.scaleX = s; mascot.scaleY = s }; start()
        }
    }
    private fun stopBreathe() { breathe?.cancel(); breathe = null; mascot.scaleX = 1f; mascot.scaleY = 1f }
    private fun detach(v: View) { (v.parent as? FrameLayout)?.removeView(v); (v.parent as? LinearLayout)?.removeView(v) }
    private fun glowCol(res: Int, a: Float) = Ui.colorWithAlpha(C(res), a)
    private fun send(action: String) = startService(Intent(this, PttService::class.java).setAction(action))

    override fun onResume() {
        super.onResume()
        val f = IntentFilter(PttService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, f)
    }
    override fun onPause() { super.onPause(); runCatching { unregisterReceiver(receiver) } }
    override fun onDestroy() { super.onDestroy(); stopBreathe(); ui.removeCallbacks(timerTick) }
}
