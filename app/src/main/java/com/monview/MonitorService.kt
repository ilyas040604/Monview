
package com.monview

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.google.firebase.database.ValueEventListener

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

    private var activeConnectionListener: ValueEventListener? = null
    private var cameraSwitchListener: ValueEventListener? = null

    private var activeCode: String? = null
    private var activeAttemptId: String? = null
    private var expectedViewerUid: String? = null

    private var running = false
    private var firstSwitchValueReceived = false
    private var lastCameraSwitchRequest = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getStringExtra("session_code")
                if (code.isNullOrBlank()) {
                    stopSelf()
                } else {
                    startCameraService(code)
                }
            }

            ACTION_STOP -> {
                stopCameraService()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_SWITCH -> switchCamera()
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
                description = "Status layanan kamera MonView"
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MonView — Kamera")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun updateStatus(message: String) {
        val code = activeCode ?: return

        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification(message))

        WebRtcSignaling.sendCameraStatus(code, message)
    }

    private fun startCameraService(code: String) {
        if (running && activeCode == code) {
            updateStatus("KAMERA AKTIF DI LATAR BELAKANG")
            return
        }

        stopCameraService()

        val cameraPermission =
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

        val audioPermission =
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

        if (!cameraPermission || !audioPermission) {
            activeCode = code
            updateStatus("IZIN KAMERA ATAU MIKROFON BELUM DIBERIKAN")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification("Menyiapkan kamera..."),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification("Menyiapkan kamera...")
                )
            }

            activeCode = code
            updateStatus("MENYIAPKAN KAMERA DAN MIKROFON...")

            // Laporkan setiap status langsung dari CameraStream.
            val stream = CameraStream(applicationContext) { message ->
                updateStatus(message)
            }

            if (!stream.start()) {
                updateStatus(
                    stream.lastError ?: "GAGAL MENYALAKAN KAMERA"
                )
                stream.stop()
                stopCameraService()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            cameraStream = stream

            val pairing = PairingStore.get(this)

            if (
                pairing == null ||
                pairing.role != "camera" ||
                pairing.pairId != code ||
                pairing.peerUid.isBlank()
            ) {
                updateStatus("DATA PAIRING KAMERA TIDAK DITEMUKAN")
                stopCameraService()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            expectedViewerUid = pairing.peerUid

            listenForCameraSwitch(code)
            listenForConnection(code, pairing.peerUid)

            running = true
            updateStatus("KAMERA AKTIF — MENUNGGU PEMANTAU")

        } catch (e: Exception) {
            updateStatus("LAYANAN KAMERA GAGAL: ${e.message}")
            stopCameraService()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun listenForConnection(
        code: String,
        viewerUid: String
    ) {
        activeConnectionListener?.let {
            WebRtcSignaling.removeActiveConnectionListener(code, it)
        }

        activeConnectionListener =
            WebRtcSignaling.listenActiveConnection(
                code,
                viewerUid
            ) { attemptId ->

                if (activeCode != code || !running) {
                    return@listenActiveConnection
                }

                if (attemptId.isNullOrBlank()) {
                    stopPeerConnection()
                    updateStatus("MENUNGGU PERMINTAAN KONEKSI PEMANTAU")
                    return@listenActiveConnection
                }

                if (attemptId == activeAttemptId) return@listenActiveConnection

                startPeerConnection(code, attemptId)
            }
    }

    private fun startPeerConnection(
        code: String,
        attemptId: String
    ) {
        if (activeCode != code || !running) return

        stopPeerConnection()
        activeAttemptId = attemptId
        updateStatus("MEMBUAT KONEKSI WEBRTC...")

        try {
            val stream = cameraStream

            if (
                stream == null ||
                stream.videoTrack == null ||
                stream.audioTrack == null
            ) {
                updateStatus("TRACK KAMERA ATAU MIKROFON TIDAK TERSEDIA")
                return
            }

            val session = WebRtcSession(
                code = code,
                attemptId = attemptId,
                isCamera = true,
                onRemoteTrack = {},
                onStatus = { message ->
                    if (
                        activeCode == code &&
                        activeAttemptId == attemptId
                    ) {
                        updateStatus(message)
                    }
                },
                onRemoteAudioTrack = {}
            )

            webRtcSession = session
            session.startCamera(stream)

        } catch (e: Exception) {
            updateStatus("GAGAL MEMULAI WEBRTC: ${e.message}")
            stopPeerConnection()
        }
    }

    private fun stopPeerConnection() {
        webRtcSession?.stop()
        webRtcSession = null
        activeAttemptId = null
    }

    private fun listenForCameraSwitch(code: String) {
        cameraSwitchListener?.let {
            WebRtcSignaling.removeCameraSwitchListener(code, it)
        }

        firstSwitchValueReceived = false
        lastCameraSwitchRequest = 0L

        cameraSwitchListener =
            WebRtcSignaling.listenCameraSwitch(code) { requestAt ->

                if (activeCode != code || !running) {
                    return@listenCameraSwitch
                }

                if (!firstSwitchValueReceived) {
                    lastCameraSwitchRequest = requestAt
                    firstSwitchValueReceived = true
                    return@listenCameraSwitch
                }

                if (requestAt > lastCameraSwitchRequest) {
                    lastCameraSwitchRequest = requestAt
                    switchCamera()
                }
            }
    }

    fun switchCamera() {
        val stream = cameraStream

        if (stream == null) {
            updateStatus("KAMERA BELUM AKTIF")
            return
        }

        stream.switchCamera { success, message ->
            updateStatus(
                if (success) {
                    "KAMERA BERHASIL DIGANTI: $message"
                } else {
                    "GAGAL MENGGANTI KAMERA: $message"
                }
            )
        }
    }

    private fun stopCameraService() {
        val code = activeCode

        if (code != null) {
            activeConnectionListener?.let {
                WebRtcSignaling.removeActiveConnectionListener(code, it)
            }

            cameraSwitchListener?.let {
                WebRtcSignaling.removeCameraSwitchListener(code, it)
            }
        }

        activeConnectionListener = null
        cameraSwitchListener = null

        stopPeerConnection()

        cameraStream?.stop()
        cameraStream = null

        activeCode = null
        expectedViewerUid = null

        running = false
        firstSwitchValueReceived = false
        lastCameraSwitchRequest = 0L
    }

    override fun onDestroy() {
        stopCameraService()
        super.onDestroy()
    }
}
