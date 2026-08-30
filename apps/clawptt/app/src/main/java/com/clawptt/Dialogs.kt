package com.clawptt

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Themed replacements for stock Material AlertDialogs so every popup matches the openclaw look —
 * a dark rounded [Ui.card] surface, Figtree type, Claw-Red pill buttons. Covers the settings
 * pickers/inputs and the chat page's conversation switcher, answer menu, and delete confirm.
 */
object Dialogs {

    /** A tappable row. `onTap` is the trailing lambda; `onLong` optional (e.g. delete a thread). */
    data class Item(
        val label: String,
        val marked: Boolean = false,          // e.g. the current selection / active conversation
        val onLong: (() -> Unit)? = null,
        val onTap: () -> Unit,
    )

    private fun C(c: Context, id: Int) = Ui.col(c, id)
    private fun dp(c: Context, v: Float) = Ui.dp(c, v)
    private fun TextView.sp(v: Float) = setTextSize(TypedValue.COMPLEX_UNIT_SP, v)

    private fun shell(ctx: Context): Pair<Dialog, LinearLayout> {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.card(ctx, C(ctx, R.color.oc_surface), 20f).apply {
                setStroke(dp(ctx, 1.5f), C(ctx, R.color.oc_stroke))
            }
            setPadding(dp(ctx, 22f), dp(ctx, 20f), dp(ctx, 22f), dp(ctx, 16f))
        }
        val dialog = Dialog(ctx).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(col)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout((ctx.resources.displayMetrics.widthPixels * 0.86f).toInt(), WRAP_CONTENT)
                setDimAmount(0.6f)
            }
        }
        return dialog to col
    }

    private fun titleView(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; typeface = Ui.figtreeBold(ctx); sp(17f)
        setTextColor(C(ctx, R.color.oc_accent_pale)); setPadding(dp(ctx, 4f), 0, 0, dp(ctx, 8f))
    }

    private fun rowView(ctx: Context, item: Item, dialog: Dialog) = TextView(ctx).apply {
        text = item.label; typeface = Ui.figtree(ctx); sp(14f)
        setTextColor(C(ctx, if (item.marked) R.color.oc_accent_light else R.color.oc_text))
        setPadding(dp(ctx, 6f), dp(ctx, 14f), dp(ctx, 6f), dp(ctx, 14f))
        isClickable = true
        setOnClickListener { dialog.dismiss(); item.onTap() }
        item.onLong?.let { lo -> setOnLongClickListener { dialog.dismiss(); lo(); true } }
    }

    private fun divider(ctx: Context) = View(ctx).apply {
        setBackgroundColor(C(ctx, R.color.oc_divider_dim))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1)
    }

    private fun pillBtn(ctx: Context, text: String, fill: Int, textColor: Int, stroke: Int, onClick: () -> Unit) =
        TextView(ctx).apply {
            this.text = text; gravity = Gravity.CENTER; typeface = Ui.figtreeSemibold(ctx); sp(12.5f)
            setTextColor(C(ctx, textColor))
            background = if (stroke != 0) Ui.pillStroke(ctx, C(ctx, stroke)) else Ui.pill(C(ctx, fill))
            setPadding(dp(ctx, 22f), dp(ctx, 11f), dp(ctx, 22f), dp(ctx, 11f))
            isClickable = true; setOnClickListener { onClick() }
        }

    private fun buttonRow(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(ctx, 14f), 0, 0)
    }

    private fun gap(ctx: Context) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(ctx, 10f), 1) }

    /** A titled (optional) list of tappable items; per-item long-press supported. */
    fun menu(ctx: Context, title: String?, items: List<Item>, closeLabel: String? = "Close") {
        val (dlg, col) = shell(ctx)
        title?.let { col.addView(titleView(ctx, it)) }
        val listCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { i, it ->
            if (i > 0) listCol.addView(divider(ctx))
            listCol.addView(rowView(ctx, it, dlg))
        }
        val h = if (items.size > 6) dp(ctx, 320f) else WRAP_CONTENT
        col.addView(ScrollView(ctx).apply { addView(listCol) }, LinearLayout.LayoutParams(MATCH_PARENT, h))
        if (closeLabel != null) col.addView(buttonRow(ctx).apply {
            addView(pillBtn(ctx, closeLabel, 0, R.color.oc_text_secondary, R.color.oc_stroke) { dlg.dismiss() })
        })
        dlg.show()
    }

    /** Title + message + Cancel / accent confirm. */
    fun confirm(ctx: Context, title: String, message: String, confirmLabel: String, onConfirm: () -> Unit) {
        val (dlg, col) = shell(ctx)
        col.addView(titleView(ctx, title))
        col.addView(TextView(ctx).apply {
            text = message; typeface = Ui.figtree(ctx); sp(12.5f)
            setTextColor(C(ctx, R.color.oc_text_secondary)); setLineSpacing(0f, 1.35f)
            setPadding(dp(ctx, 4f), 0, dp(ctx, 4f), 0)
        })
        col.addView(buttonRow(ctx).apply {
            addView(pillBtn(ctx, "Cancel", 0, R.color.oc_text_secondary, R.color.oc_stroke) { dlg.dismiss() })
            addView(gap(ctx))
            addView(pillBtn(ctx, confirmLabel, R.color.oc_accent, R.color.oc_on_accent, 0) { dlg.dismiss(); onConfirm() })
        })
        dlg.show()
    }

    /** Title + text field + Cancel / Save. */
    fun input(ctx: Context, title: String, current: String, password: Boolean, onSave: (String) -> Unit) {
        val (dlg, col) = shell(ctx)
        col.addView(titleView(ctx, title))
        val field = EditText(ctx).apply {
            setText(current); typeface = Ui.figtree(ctx); sp(13.5f)
            setTextColor(C(ctx, R.color.oc_text)); setHintTextColor(C(ctx, R.color.oc_text_tertiary))
            background = Ui.card(ctx, C(ctx, R.color.oc_bg), 10f).apply { setStroke(dp(ctx, 1.5f), C(ctx, R.color.oc_stroke)) }
            setPadding(dp(ctx, 14f), dp(ctx, 12f), dp(ctx, 14f), dp(ctx, 12f))
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        else InputType.TYPE_CLASS_TEXT
            setSelection(current.length)
        }
        col.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(ctx, 6f) })
        col.addView(buttonRow(ctx).apply {
            addView(pillBtn(ctx, "Cancel", 0, R.color.oc_text_secondary, R.color.oc_stroke) { dlg.dismiss() })
            addView(gap(ctx))
            addView(pillBtn(ctx, "Save", R.color.oc_accent, R.color.oc_on_accent, 0) { dlg.dismiss(); onSave(field.text.toString()) })
        })
        dlg.show()
    }
}
