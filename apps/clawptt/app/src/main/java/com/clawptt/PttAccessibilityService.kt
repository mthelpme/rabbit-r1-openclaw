package com.clawptt

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Captures the hardware PTT button (remapped to KEYCODE_BUTTON_1 by the keylayout module)
 * globally — including on the lock screen — and drives PttService with hold/release events.
 *
 * The event is consumed (return true) so the button does nothing else while ClawPTT owns it.
 *
 * This service is alive from boot, so it also hosts the power saver (see [AirplaneManager]):
 * it watches screen on/off to arm/cancel the idle radio-cut, and a PTT press counts as activity.
 */
class PttAccessibilityService : AccessibilityService() {

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> AirplaneManager.onScreenOff(this@PttAccessibilityService)
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT ->
                    AirplaneManager.onUserActive(this@PttAccessibilityService)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching {
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            })
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BUTTON_1) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Ignore auto-repeat while held.
                if (event.repeatCount == 0) {
                    AirplaneManager.onUserActive(this)   // restore radios before the turn needs the network
                    send(PttService.ACTION_PTT_DOWN)
                }
            }
            KeyEvent.ACTION_UP -> send(PttService.ACTION_PTT_UP)
        }
        return true
    }

    private fun send(action: String) {
        val i = Intent(this, PttService::class.java).setAction(action)
        startForegroundService(i)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(screenReceiver) }
    }
}
