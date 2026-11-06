
package com.monview

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.firebase.database.ValueEventListener
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.webrtc.AudioTrack
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class MainActivity : ComponentActivity() {

    private val gold = Color.rgb(212, 175, 55)
    private val dark = Color.rgb(10, 10, 10)

    private lateinit var status: TextView
    private lateinit var qrImage: ImageView
    private lateinit var remoteRenderer: SurfaceViewRenderer
    private lateinit var switchCameraButton: Button
    private lateinit var nightVisionButton: Button
    private lateinit var audioButton: Button

    private var eglBase: EglBase? = null
    private var webRtcSession: WebRtcSession? = null
    private var viewerListener: ValueEventListener? = null
    private var nightVisionSink: NightVisionVideoSink? = null
    private var remoteAudioTrack: AudioTrack? = null

    private var activeCode: String? = null
    private var startingCamera = false
    private var cameraServiceStarted = false
    private var nightVisionEnabled = false
    private var audioEnabled = true

    private val scannerLauncher =
        registerForActivityResult(ScanContract()) { result ->
            val code = result.contents

            if (code.isNullOrBlank()) {
                status.text = "Pemindaian QR dibatalkan."
            } else {
                joinCameraSession(code.trim())
            }
        }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            if (results.values.all { it }) {
                createCameraSession()
            } else {
                status.text = "Izin kamera dan mikrofon diperlukan."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = dark
        window.navigationBarColor = dark

        eglBase = EglBase.create()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(dark)
        }

        val brand = TextView(this).apply {
            text = "M W"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(gold)
        }

        status = TextView(this).apply {
            text = "MONVIEW SIAP"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(16, 20, 16, 20)
        }

        qrImage = ImageView(this).apply {
            visibility = View.GONE
            adjustViewBounds = true
        }

        remoteRenderer = SurfaceViewRenderer(this).apply {
            init(eglBase!!.eglBaseContext, null)
            setEnableHardwareScaler(true)
            visibility = View.GONE
        }

        nightVisionSink = NightVisionVideoSink(remoteRenderer)

        val cameraButton = makeButton("MULAI SEBAGAI KAMERA") {
            requestCameraPermissions()
        }

        val viewerButton = makeButton("SAMBUNG SEBAGAI PEMANTAU") {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Pindai QR MonView")
                setBeepEnabled(false)
                setOrientationLocked(true)
            }

            scannerLauncher.launch(options)
        }

        switchCameraButton = makeButton("GANTI KAMERA") {
            val code = activeCode ?: return@makeButton

            WebRtcSignaling.requestCameraSwitch(code) { success, message ->
                runOnUiThread {
                    status.text = if (success) {
                        "Perintah ganti kamera terkirim."
                    } else {
                        "Gagal: $message"
                    }
                }
            }
        }.apply {
            visibility = View.GONE
        }

        nightVisionButton = makeButton("NIGHT VISION: MATI") {
            nightVisionEnabled = !nightVisionEnabled
            nightVisionSink?.setNightVisionEnabled(nightVisionEnabled)

            nightVisionButton.text = if (nightVisionEnabled) {
                "NIGHT VISION: AKTIF"
            } else {
                "NIGHT VISION: MATI"
            }
        }.apply {
            visibility = View.GONE
        }

        audioButton = makeButton("SUARA HP: AKTIF") {
            audioEnabled = !audioEnabled
            remoteAudioTrack?.setEnabled(audioEnabled)

            audioButton.text = if (audioEnabled) {
                "SUARA HP: AKTIF"
            } else {
                "SENYAP: AKTIF"
            }
        }.apply {
            visibility = View.GONE
        }

        val stopButton = makeButton("HENTIKAN KONEKSI") {
            stopCurrentSession()
            status.text = "KONEKSI DIHENTIKAN"
        }

        root.addView(brand, fullWidth())
        root.addView(status, fullWidth())
        root.addView(qrImage, LinearLayout.LayoutParams(220, 220))
        root.addView(
            remoteRenderer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                360
            )
        )
        root.addView(cameraButton, fullWidth())
        root.addView(viewerButton, fullWidth())
        root.addView(switchCameraButton, fullWidth())
        root.addView(nightVisionButton, fullWidth())
        root.addView(audioButton, fullWidth())
        root.addView(stopButton, fullWidth())

        setContentView(ScrollView(this).apply {
            addView(root)
        })
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun makeButton(
        label: String,
        action: () -> Unit
    ) = Button(this).apply {
        text = label
        setTextColor(gold)
        setBackgroundColor(Color.rgb(35, 35, 35))
        setOnClickListener { action() }
    }

    private fun requestCameraPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) !=
                PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            createCameraSession()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun createCameraSession() {
        stopCurrentSession()
        status.text = "MEMBUAT QR..."

        SessionManager.createSession { success, result ->
            runOnUiThread {
                if (!success) {
                    status.text = "GAGAL MEMBUAT QR\n$result"
                    return@runOnUiThread
                }

                activeCode = result
                qrImage.setImageBitmap(QrCodeGenerator.generate(result))
                qrImage.visibility = View.VISIBLE
                status.text = "MENUNGGU HP PEMANTAU\nQR berlaku 5 menit."

                viewerListener =
                    SessionManager.listenForViewer(result, this) { joined, message ->
                        runOnUiThread {
                            if (joined && !startingCamera) {
                                startingCamera = true
                                startCameraConnection(result)
                            } else if (!joined) {
                                status.text = message
                            }
                        }
                    }
            }
        }
    }

    private fun startCameraConnection(code: String) {
        status.text = "MENYIAPKAN LAYANAN KAMERA..."

        try {
            val intent = Intent(
                this,
                MonitorService::class.java
            ).apply {
                action = MonitorService.ACTION_START
                putExtra("session_code", code)
            }

            ContextCompat.startForegroundService(this, intent)

            cameraServiceStarted = true

            viewerListener?.let {
                SessionManager.removeViewerListener(code, it)
            }
            viewerListener = null

            status.text = "KAMERA AKTIF\nLayanan latar belakang dijalankan."

        } catch (e: Exception) {
            startingCamera = false
            cameraServiceStarted = false
            status.text = "GAGAL MENJALANKAN LAYANAN\n${e.message}"
        }
    }

    private fun joinCameraSession(code: String) {
        stopCurrentSession()
        status.text = "MEMERIKSA QR..."

        SessionManager.joinSession(code, this) { success, message ->
            runOnUiThread {
                if (!success) {
                    status.text = "GAGAL BERGABUNG\n$message"
                    return@runOnUiThread
                }

                activeCode = code
                remoteRenderer.visibility = View.VISIBLE
                switchCameraButton.visibility = View.VISIBLE
                nightVisionButton.visibility = View.VISIBLE
                audioButton.visibility = View.VISIBLE

                try {
                    WebRtcManager.initialize(this)

                    webRtcSession = WebRtcSession(
                        code = code,
                        isCamera = false,
                        onRemoteTrack = { track: VideoTrack ->
                            runOnUiThread {
                                nightVisionSink?.let {
                                    track.addSink(it)
                                }
                                status.text = "VIDEO DITERIMA"
                            }
                        },
                        onStatus = { messageText ->
                            runOnUiThread {
                                status.text = messageText
                            }
                        },
                        onRemoteAudioTrack = { track: AudioTrack ->
                            runOnUiThread {
                                remoteAudioTrack = track
                                track.setEnabled(audioEnabled)
                            }
                        }
                    )

                    webRtcSession?.startViewer()
                    status.text = "TERHUBUNG\nMenunggu video..."

                } catch (e: Exception) {
                    status.text = "GAGAL MEMULAI\n${e.message}"
                }
            }
        }
    }

    private fun stopCurrentSession() {
        val code = activeCode

        if (code != null && viewerListener != null) {
            SessionManager.removeViewerListener(
                code,
                viewerListener!!
            )
        }

        viewerListener = null

        if (cameraServiceStarted) {
            val intent = Intent(
                this,
                MonitorService::class.java
            ).apply {
                action = MonitorService.ACTION_STOP
            }

            startService(intent)
        }

        cameraServiceStarted = false

        remoteAudioTrack?.setEnabled(false)
        remoteAudioTrack = null

        webRtcSession?.stop()
        webRtcSession = null

        activeCode = null
        startingCamera = false
        audioEnabled = true
        nightVisionEnabled = false

        nightVisionSink?.setNightVisionEnabled(false)

        qrImage.visibility = View.GONE
        remoteRenderer.visibility = View.GONE
        switchCameraButton.visibility = View.GONE
        nightVisionButton.visibility = View.GONE
        audioButton.visibility = View.GONE

        nightVisionButton.text = "NIGHT VISION: MATI"
        audioButton.text = "SUARA HP: AKTIF"
    }

    override fun onDestroy() {
        val code = activeCode

        if (code != null && viewerListener != null) {
            SessionManager.removeViewerListener(
                code,
                viewerListener!!
            )
        }

        viewerListener = null

        // Jangan hentikan MonitorService saat halaman aplikasi ditutup.
        // Layanan kamera tetap berjalan di latar belakang.

        remoteAudioTrack?.setEnabled(false)
        remoteAudioTrack = null

        webRtcSession?.stop()
        webRtcSession = null

        remoteRenderer.release()
        eglBase?.release()
        eglBase = null

        super.onDestroy()
    }
}
