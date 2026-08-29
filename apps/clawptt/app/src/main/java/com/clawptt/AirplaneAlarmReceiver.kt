package com.clawptt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/** Fired by the idle alarm: cut the radios if the screen is still off and the feature is on. */
class AirplaneAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != ACTION_CUT) return
        if (!Config(ctx).powerSaverEnabled) return
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm != null && pm.isInteractive) return   // user woke it before the timer fired
        AirplaneManager.enable(ctx)
    }

    companion object { const val ACTION_CUT = "com.clawptt.AIRPLANE_CUT" }
}
