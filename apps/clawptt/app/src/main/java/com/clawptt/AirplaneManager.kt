package com.clawptt

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Power saver: cut all radios (airplane mode) after the screen has been off for a while, and
 * restore them the moment the user wakes the device (screen-on) or presses PTT. Requires root
 * (Magisk grants su on first use). We only ever undo airplane mode that *we* turned on, so a
 * user-initiated airplane mode is respected.
 */
object AirplaneManager {

    private const val REQ = 4242

    @Volatile private var cached: Config? = null
    private fun cfg(ctx: Context): Config = cached ?: Config(ctx.applicationContext).also { cached = it }

    private fun runRoot(cmd: String) {
        Thread { runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() } }.start()
    }

    fun isOn(ctx: Context): Boolean = runCatching {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    }.getOrDefault(false)

    fun enable(ctx: Context) {
        cfg(ctx).airplaneSetByUs = true
        runRoot("cmd connectivity airplane-mode enable || " +
            "(settings put global airplane_mode_on 1; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true)")
    }

    fun disable(ctx: Context) {
        cfg(ctx).airplaneSetByUs = false
        runRoot("cmd connectivity airplane-mode disable || " +
            "(settings put global airplane_mode_on 0; am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false)")
    }

    private fun alarmPi(ctx: Context): PendingIntent {
        val i = Intent(ctx, AirplaneAlarmReceiver::class.java).setAction(AirplaneAlarmReceiver.ACTION_CUT)
        return PendingIntent.getBroadcast(
            ctx, REQ, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun scheduleIdleCut(ctx: Context) {
        val c = cfg(ctx)
        if (!c.powerSaverEnabled) return
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val at = System.currentTimeMillis() + c.airplaneIdleMinutes.coerceAtLeast(1) * 60_000L
        runCatching { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmPi(ctx)) }
    }

    fun cancelIdleCut(ctx: Context) {
        (ctx.getSystemService(AlarmManager::class.java) ?: return).let {
            runCatching { it.cancel(alarmPi(ctx)) }
        }
    }

    /** Screen turned off (locked): arm the idle cut. */
    fun onScreenOff(ctx: Context) {
        if (cfg(ctx).powerSaverEnabled) scheduleIdleCut(ctx)
    }

    /** User woke or used the device (screen-on / PTT): cancel the pending cut, restore radios if we cut them. */
    fun onUserActive(ctx: Context) {
        cancelIdleCut(ctx)
        if (cfg(ctx).airplaneSetByUs) disable(ctx)
    }

    /**
     * Fire a root command right now so Magisk prompts for the grant without waiting for the idle
     * timer. Uses the airplane-mode *status* query (no side effects — doesn't cut connectivity) and
     * reports whether root + the command path work. `done` is called on a background thread.
     */
    fun test(ctx: Context, done: (Boolean) -> Unit) {
        Thread {
            val ok = runCatching {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "cmd connectivity airplane-mode")).waitFor() == 0
            }.getOrDefault(false)
            done(ok)
        }.start()
    }

    /** Feature turned off / never leave the user stranded offline. */
    fun disengage(ctx: Context) {
        cancelIdleCut(ctx)
        if (cfg(ctx).airplaneSetByUs || isOn(ctx)) disable(ctx)
    }
}
