package com.r1motor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts auto-rotate at boot if the user enabled it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        val p = ctx.getSharedPreferences("m", Context.MODE_PRIVATE)
        if (p.getBoolean("auto", false)) CameraMotorService.start(ctx)
        if (p.getBoolean("rotate", false)) RotateService.start(ctx)
    }
}
