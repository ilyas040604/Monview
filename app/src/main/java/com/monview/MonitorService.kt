
package com.monview

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Binder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class MonitorService : Service() {

    companion object {
        const val ACTION_START = "com.monview.action.START_CAMERA"
        const val ACTION_STOP = "com.monview.action.STOP_CAMERA"
        const val ACTION_SWITCH = "com.monview.action.SWITCH_CAMERA"

        private const val CHANNEL_ID = "monview_camera_service"
        private const val NOTIFICATION_ID = 41
    }

    inner class LocalBinder : Binder() {
        fun getService(): MonitorService = this@MonitorService
    }

    private val binder = LocalBinder()

    private var cameraStream: CameraStream? = null
    private var webRtcSession: WebRtcSession? = null
    private var activeCode: String? = null
    private var running = false

    var onStatus: ((String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getStringExtra("session_code")
                if (!code.isNullOrBlank()) {
                    startCameraService(code)
                } else {
                    stopSelf()
                }
            }

            ACTION_STOP -> {
                stopCameraService()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_SWITCH -> {
                switchCamera()
            }
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MonView Kamera Aktif",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menampilkan status kamera MonView"
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MonView — Kamera Aktif")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startCameraService(code: String) {
        if (running && activeCode == code) return

        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            onStatus?.invoke("Izin kamera dan mikrofon belum diberikan.")
            stopSelf()
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification("Menyiapkan kamera dan mikrofon..."),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification("Menyiapkan kamera dan mikrofon...")
                )
            }

            activeCode = code

            val stream = CameraStream(applicationContext)
            if (!stream.start()) {
                onStatus?.invoke("Kamera atau mikrofon gagal dinyalakan.")
                stopCameraService()
                stopSelf()
                return
            }

            cameraStream = stream

            val session = WebRtcSession(
                code = code,
                isCamera = true,
                onRemoteTrack = {},
                onStatus = { message ->
                    updateNotification(message)
                    onStatus?.invoke(message)
                }
            )

            webRtcSession = session
            session.startCamera(stream)

            running = true
            updateNotification("Kamera dan mikrofon sedang aktif.")
            onStatus?.invoke("KAMERA AKTIF DI LATAR BELAKANG")

        } catch (e: Exception) {
            onStatus?.invoke("Layanan kamera gagal: ${e.message}")
            stopCameraService()
            stopSelf()
        }
    }

    private fun updateNotification(message: String) {
        val manager =
            getSystemService(NotificationManager::class.java)

        manager.notify(
            NOTIFICATION_ID,
            createNotification(message)
        )
    }

    fun switchCamera() {
        cameraStream?.switchCamera { success, message ->
            onStatus?.invoke(
                if (success) message else "Gagal mengganti kamera: $message"
            )
        }
    }

    private fun stopCameraService() {
        webRtcSession?.stop()
        webRtcSession = null

        cameraStream?.stop()
        cameraStream = null

        activeCode = null
        running = false
    }

    override fun onDestroy() {
        stopCameraService()
        super.onDestroy()
    }
}
