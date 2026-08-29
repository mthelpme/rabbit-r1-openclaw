package com.r1motor

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val bg = Color.parseColor("#14110D")
    private val accent = Color.parseColor("#D67F48")
    private val sage = Color.parseColor("#8FA073")
    private val textc = Color.parseColor("#EEE7DB")
    private val sub = Color.parseColor("#82796A")

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val prefs = getSharedPreferences("m", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(dp(28), dp(40), dp(28), dp(28))
        }
        root.addView(title("R1 Tools", accent, 22f))
        root.addView(text("Camera motor + software auto-rotate for the GSI.", sub, 12f).apply { setPadding(0, dp(6), 0, dp(24)) })

        root.addView(btn("Rear · photos / QR", sage, Color.parseColor("#1C2318")) { rotate(Motor.REAR) }, lp(56))
        root.addView(space(12))
        root.addView(btn("Front · selfie", accent, Color.parseColor("#241A12")) { rotate(Motor.FRONT) }, lp(56))
        root.addView(space(12))
        root.addView(btn("Stow · hide", Color.parseColor("#2E2B25"), textc) { rotate(Motor.STOW) }, lp(56))
        root.addView(space(28))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text("Auto-rotate on camera open", textc, 14f))
        col.addView(text("Points rear when a camera app opens; stows on close", sub, 11f))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(SwitchCompat(this).apply {
            isChecked = prefs.getBoolean("auto", false)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.parseColor("#241A12"), sub))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, Color.parseColor("#2E2B25")))
            setOnCheckedChangeListener { _, v ->
                prefs.edit().putBoolean("auto", v).apply()
                if (v) CameraMotorService.start(this@MainActivity) else CameraMotorService.stop(this@MainActivity)
            }
        })
        root.addView(row)
        root.addView(space(16))
        root.addView(switchRow("Software auto-rotate", "Rotates from the accelerometer (fixes the GSI)",
            prefs.getBoolean("rotate", false)) { v ->
            prefs.edit().putBoolean("rotate", v).apply()
            if (v) RotateService.start(this) else {
                RotateService.stop(this)
                Thread { runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put system user_rotation 0")).waitFor() } }.start()
            }
        })
        root.addView(space(24))
        status = text("Tap a position — grant root when Magisk asks.", sub, 11f)
        root.addView(status)
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(root) })
    }

    private fun rotate(pos: Int) {
        Thread {
            val ok = Motor.set(pos)
            MotorTileService.pos = pos
            runOnUiThread {
                status.text = if (ok) "Rotated to ${name(pos)}."
                else "Failed — install the sepolicy Magisk module + reboot, and grant root to this app."
            }
        }.start()
    }

    private fun name(p: Int) = when (p) { Motor.FRONT -> "front"; Motor.REAR -> "rear"; else -> "stow" }

    private fun switchRow(titleText: String, subText: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(titleText, textc, 14f)); col.addView(text(subText, sub, 11f))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(SwitchCompat(this).apply {
            isChecked = initial
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.parseColor("#241A12"), sub))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accent, Color.parseColor("#2E2B25")))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        })
        return row
    }

    private fun title(t: String, color: Int, sp: Float) = TextView(this).apply {
        text = t; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }
    private fun text(t: String, color: Int, sp: Float) = TextView(this).apply {
        text = t; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }
    private fun btn(label: String, fill: Int, textColor: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; setTextColor(textColor); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = GradientDrawable().apply { cornerRadius = 999f; setColor(fill) }
        isClickable = true; setOnClickListener { onClick() }
    }
    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(h)) }
    private fun lp(h: Int) = LinearLayout.LayoutParams(MATCH_PARENT, dp(h))
}
