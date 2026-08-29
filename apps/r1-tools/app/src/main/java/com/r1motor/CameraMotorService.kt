package com.r1motor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.IBinder

/**
 * Auto-rotates the camera: when any app opens the camera the lens points REAR (correct
 * orientation), and stows when released. Uses CameraManager availability (no polling).
 */
class CameraMotorService : Service() {

    private lateinit var cm: CameraManager
    private val cb = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) { Thread { Motor.set(Motor.REAR) }.start() }
        override fun onCameraAvailable(cameraId: String) { Thread { Motor.set(Motor.STOW) }.start() }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cm.registerAvailabilityCallback(cb, null)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int = START_STICKY
    override fun onDestroy() { runCatching { cm.unregisterAvailabilityCallback(cb) }; super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val ch = "motor"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ch, "Camera Motor", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, ch)
            .setContentTitle("Camera auto-rotate on")
            .setContentText("Points the lens rear when the camera opens")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(7, n)
    }

    companion object {
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, CameraMotorService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, CameraMotorService::class.java))
    }
}
