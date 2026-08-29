package com.r1motor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.view.Surface
import kotlin.math.abs

/**
 * Software auto-rotate: reads the accelerometer (which works on this device) and forces
 * user_rotation (which the framework honors), bypassing the broken sensor-rotation path.
 */
class RotateService : Service(), SensorEventListener {

    private lateinit var sm: SensorManager
    @Volatile private var current = -1
    private var lastChange = 0L

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        exec("settings put system accelerometer_rotation 0")  // so forced user_rotation applies
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
        if (abs(z) > 8.5f) return                 // lying flat -> keep current
        val target = when {
            y >= 6f -> Surface.ROTATION_0          // upright portrait
            x >= 6f -> Surface.ROTATION_90         // landscape
            x <= -6f -> Surface.ROTATION_270
            else -> return                          // upside-down / ambiguous -> ignore
        }
        val now = System.currentTimeMillis()
        if (target != current && now - lastChange > 600) {
            current = target; lastChange = now
            exec("settings put system user_rotation $target")
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun exec(cmd: String) {
        Thread { runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() } }.start()
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY
    override fun onDestroy() { runCatching { sm.unregisterListener(this) }; super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val ch = "rotate"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ch, "Auto-rotate", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, ch)
            .setContentTitle("Auto-rotate active")
            .setContentText("Rotates from the accelerometer")
            .setSmallIcon(android.R.drawable.ic_menu_always_landscape_portrait)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(8, n)
    }

    companion object {
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, RotateService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, RotateService::class.java))
    }
}
