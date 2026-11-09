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
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.database.ValueEventListener

class MonitorService : Service() {

    companion object {
        const val ACTION_START = "com.monview.action.START_CAMERA"
        const val ACTION_STOP = "com.monview.action.STOP_CAMERA"
        const val ACTION_SWITCH = "com.monview.action.SWITCH_CAMERA"

        private const val CHANNEL_ID = "monview_camera_service"
        private const val NOTIFICATION_ID = 41
        private const val TAG = "MonViewCameraService"
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

    @Volatile
    private var running = false

    @Volatile
    private var starting = false

    private var firstSwitchValueReceived = false
    private var lastCameraSwitchRequest = 0L

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

                if (code.isNullOrBlank()) {
                    Log.e(TAG, "Kode sesi kosong")
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
                description = "Status layanan kamera MonView"
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        message: String
    ): Notification {

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MonView — Kamera")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun updateStatus(message: String) {
        val code = activeCode ?: return

        try {
            getSystemService(NotificationManager::class.java)
                .notify(
                    NOTIFICATION_ID,
                    createNotification(message)
                )
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memperbarui notifikasi", e)
        }

        WebRtcSignaling.sendCameraStatus(code, message)

        Log.i(TAG, message)
    }

    private fun startCameraService(code: String) {

        // Mencegah layanan yang sama dimulai berulang kali.
        if (activeCode == code && running) {
            updateStatus("KAMERA AKTIF DI LATAR BELAKANG")
            return
        }

        if (activeCode == code && starting) {
            updateStatus("KAMERA SEDANG DISIAPKAN")
            return
        }

        // Bersihkan layanan lama sebelum membuat sesi baru.
        stopCameraService()

        starting = true
        running = false
        activeCode = code

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
            updateStatus(
                "IZIN KAMERA ATAU MIKROFON BELUM DIBERIKAN"
            )

            stopCameraService()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        try {

            // Aktifkan foreground service sebelum memulai kamera.
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

            updateStatus(
                "MENYIAPKAN KAMERA DAN MIKROFON..."
            )

            // Membuat stream kamera dan audio.
            val stream = CameraStream(applicationContext) { message ->
                updateStatus(message)
            }

            if (!stream.start()) {

                updateStatus(
                    stream.lastError
                        ?: "GAGAL MENYALAKAN KAMERA"
                )

                stream.stop()

                stopCameraService()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            cameraStream = stream

            // Periksa pairing kamera yang telah disimpan.
            val pairing = PairingStore.get(this)

            if (
                pairing == null ||
                pairing.role != "camera" ||
                pairing.pairId != code ||
                pairing.peerUid.isBlank()
            ) {

                updateStatus(
                    "DATA PAIRING KAMERA TIDAK DITEMUKAN"
                )

                stopCameraService()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }

            expectedViewerUid = pairing.peerUid

            /*
             * PENTING:
             *
             * Status running harus aktif SEBELUM listener Firebase
             * dipasang. Dengan begitu, permintaan koneksi pertama
             * tidak terlewat oleh pemeriksaan status layanan.
             */

            running = true
            starting = false

            updateStatus(
                "KAMERA AKTIF — MENUNGGU PEMANTAU"
            )

            // Pasang listener setelah kamera benar-benar siap.
            listenForCameraSwitch(code)

            listenForConnection(
                code,
                pairing.peerUid
            )

            Log.i(
                TAG,
                "LAYANAN KAMERA SIAP UNTUK KODE SESI"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "GAGAL MEMULAI LAYANAN KAMERA",
                e
            )

            updateStatus(
                "LAYANAN KAMERA GAGAL: ${e.message}"
            )

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
            WebRtcSignaling.removeActiveConnectionListener(
                code,
                it
            )
        }

        activeConnectionListener =
            WebRtcSignaling.listenActiveConnection(
                code,
                viewerUid
            ) { attemptId ->

                if (
                    activeCode != code ||
                    !running
                ) {
                    return@listenActiveConnection
                }

                // Tidak ada permintaan koneksi aktif.
                if (attemptId.isNullOrBlank()) {

                    if (activeAttemptId != null) {
                        stopPeerConnection()

                        updateStatus(
                            "MENUNGGU PERMINTAAN KONEKSI PEMANTAU"
                        )
                    }

                    return@listenActiveConnection
                }

                // Jangan membuat koneksi dua kali untuk attempt yang sama.
                if (attemptId == activeAttemptId) {
                    return@listenActiveConnection
                }

                Log.i(
                    TAG,
                    "PERMINTAAN KONEKSI BARU DITERIMA"
                )

                startPeerConnection(
                    code,
                    attemptId
                )
            }
    }

    private fun startPeerConnection(
        code: String,
        attemptId: String
    ) {

        if (
            activeCode != code ||
            !running
        ) {
            return
        }

        stopPeerConnection()

        activeAttemptId = attemptId

        updateStatus(
            "MEMBUAT KONEKSI WEBRTC..."
        )

        try {

            val stream = cameraStream

            if (
                stream == null ||
                stream.videoTrack == null ||
                stream.audioTrack == null
            ) {

                updateStatus(
                    "TRACK KAMERA ATAU MIKROFON TIDAK TERSEDIA"
                )

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
                        activeAttemptId == attemptId &&
                        running
                    ) {
                        updateStatus(message)
                    }
                },

                onRemoteAudioTrack = {}
            )

            webRtcSession = session

            session.startCamera(stream)

        } catch (e: Exception) {

            Log.e(
                TAG,
                "WEBRTC CAMERA ERROR",
                e
            )

            updateStatus(
                "GAGAL MEMULAI WEBRTC: ${e.message}"
            )

            stopPeerConnection()
        }
    }

    private fun stopPeerConnection() {

        val session = webRtcSession

        webRtcSession = null
        activeAttemptId = null

        try {
            session?.stop()
        } catch (e: Exception) {
            Log.e(
                TAG,
                "GAGAL MENGHENTIKAN PEER CONNECTION",
                e
            )
        }
    }

    private fun listenForCameraSwitch(
        code: String
    ) {

        cameraSwitchListener?.let {
            WebRtcSignaling.removeCameraSwitchListener(
                code,
                it
            )
        }

        firstSwitchValueReceived = false
        lastCameraSwitchRequest = 0L

        cameraSwitchListener =
            WebRtcSignaling.listenCameraSwitch(code) { requestAt ->

                if (
                    activeCode != code ||
                    !running
                ) {
                    return@listenCameraSwitch
                }

                // Abaikan nilai awal Firebase.
                if (!firstSwitchValueReceived) {

                    lastCameraSwitchRequest = requestAt
                    firstSwitchValueReceived = true

                    return@listenCameraSwitch
                }

                // Proses hanya permintaan baru.
                if (requestAt > lastCameraSwitchRequest) {

                    lastCameraSwitchRequest = requestAt

                    Log.i(
                        TAG,
                        "PERINTAH GANTI KAMERA DITERIMA"
                    )

                    switchCamera()
                }
            }
    }

    fun switchCamera() {

        if (!running) {
            updateStatus("KAMERA BELUM AKTIF")
            return
        }

        val stream = cameraStream

        if (stream == null) {
            updateStatus("CAMERA STREAM TIDAK TERSEDIA")
            return
        }

        stream.switchCamera { success, message ->

            if (!running) return@switchCamera

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

        // Nonaktifkan status terlebih dahulu agar callback lama
        // tidak memulai koneksi baru saat proses penghentian.
        running = false
        starting = false

        val code = activeCode

        activeConnectionListener?.let { listener ->

            if (code != null) {
                WebRtcSignaling.removeActiveConnectionListener(
                    code,
                    listener
                )
            }
        }

        cameraSwitchListener?.let { listener ->

            if (code != null) {
                WebRtcSignaling.removeCameraSwitchListener(
                    code,
                    listener
                )
            }
        }

        activeConnectionListener = null
        cameraSwitchListener = null

        firstSwitchValueReceived = false
        lastCameraSwitchRequest = 0L

        // Hentikan WebRTC sebelum kamera dilepas.
        stopPeerConnection()

        try {
            cameraStream?.stop()
        } catch (e: Exception) {
            Log.e(
                TAG,
                "GAGAL MENGHENTIKAN CAMERA STREAM",
                e
            )
        }

        cameraStream = null

        activeCode = null
        activeAttemptId = null
        expectedViewerUid = null

        Log.i(
            TAG,
            "LAYANAN KAMERA DIHENTIKAN"
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Jangan menghentikan kamera hanya karena activity
        // dihapus dari recent apps. Foreground service tetap berjalan.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopCameraService()
        super.onDestroy()
    }
}