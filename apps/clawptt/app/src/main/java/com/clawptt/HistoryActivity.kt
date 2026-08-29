package com.clawptt

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Scrollable conversation log with copy / share per turn. */
class HistoryActivity : AppCompatActivity() {

    private val C = { id: Int -> Ui.col(this, id) }
    private fun dp(v: Float) = Ui.dp(this, v)
    private lateinit var list: LinearLayout

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(C(R.color.oc_bg))
        }
        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(26f), dp(24f), dp(26f), dp(12f))
        }
        header.addView(TextView(this).apply {
            text = "History"; typeface = Ui.figtreeBold(this@HistoryActivity)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); setTextColor(C(R.color.oc_accent_pale))
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(btn("Clear", 0, C(R.color.oc_text_secondary), C(R.color.oc_stroke)) {
            History.clear(this); render()
        }.apply { setPadding(dp(20f), dp(9f), dp(20f), dp(9f)) })
        root.addView(header)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), 0, dp(20f), dp(24f)) }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        render()
    }

    private fun render() {
        list.removeAllViews()
        val turns = History.all(this)
        if (turns.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "No conversations yet."; typeface = Ui.figtree(this@HistoryActivity)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); setTextColor(C(R.color.oc_text_quaternary))
                setPadding(dp(6f), dp(40f), 0, 0)
            })
            return
        }
        turns.forEach { list.addView(card(it)); list.addView(gap(12)) }
    }

    private fun card(turn: History.Turn): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = Ui.card(this@HistoryActivity, C(R.color.oc_surface), 12f)
            setPadding(dp(16f), dp(14f), dp(16f), dp(12f))
        }
        c.addView(text(DateUtils.getRelativeTimeSpanString(turn.time).toString(), C(R.color.oc_text_quaternary), 10f, false))
        val q = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8f), 0, dp(10f)) }
        q.addView(ImageView(this).apply { setImageResource(R.drawable.ic_mic); setColorFilter(C(R.color.oc_text_tertiary)) },
            LinearLayout.LayoutParams(dp(16f), dp(16f)).apply { rightMargin = dp(9f); topMargin = dp(2f) })
        q.addView(text(turn.question, C(R.color.oc_text_tertiary), 11.5f, false))
        c.addView(q)
        c.addView(View(this).apply { setBackgroundColor(C(R.color.oc_divider_dim)); layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1) })
        c.addView(text(turn.answer, C(R.color.oc_text_bright), 12.5f, false).apply { setPadding(0, dp(10f), 0, dp(12f)) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(btn("Copy", 0, C(R.color.oc_text_secondary), C(R.color.oc_stroke)) { copy(turn.answer) }
            .apply { setPadding(dp(20f), dp(8f), dp(20f), dp(8f)) })
        actions.addView(gap(0).apply { layoutParams = LinearLayout.LayoutParams(dp(10f), 1) })
        actions.addView(btn("Share", 0, C(R.color.oc_text_secondary), C(R.color.oc_stroke)) { share(turn) }
            .apply { setPadding(dp(20f), dp(8f), dp(20f), dp(8f)) })
        c.addView(actions)
        return c
    }

    private fun copy(t: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("openclaw", t))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun share(turn: History.Turn) = startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Q: ${turn.question}\n\nA: ${turn.answer}"), "Share"))

    private fun text(s: String, color: Int, sp: Float, bold: Boolean) = TextView(this).apply {
        text = s; typeface = if (bold) Ui.figtreeSemibold(this@HistoryActivity) else Ui.figtree(this@HistoryActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
    }
    private fun btn(t: String, fill: Int, textCol: Int, stroke: Int, onClick: () -> Unit) = TextView(this).apply {
        text = t; gravity = Gravity.CENTER; typeface = Ui.figtreeSemibold(this@HistoryActivity)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(textCol)
        background = if (stroke != 0) Ui.pillStroke(this@HistoryActivity, stroke) else Ui.pill(fill)
        isClickable = true; setOnClickListener { onClick() }
    }
    private fun gap(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(h.toFloat())) }
}
