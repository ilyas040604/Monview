
package com.monview

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.firebase.database.ValueEventListener
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var qrImage: ImageView
    private lateinit var remoteRenderer: SurfaceViewRenderer

    private var eglBase: EglBase? = null
    private var cameraStream: CameraStream? = null
    private var webRtcSession: WebRtcSession? = null
    private var viewerListener: ValueEventListener? = null
    private var activeCode: String? = null
    private var startingCamera = false

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
            if (results.isNotEmpty() &&
                results.values.all { it }
            ) {
                createCameraSession()
            } else {
                status.text = "Izin kamera dan mikrofon belum lengkap."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        eglBase = EglBase.create()

        status = TextView(this).apply {
            text = "MONVIEW\n\nPilih peran perangkat."
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(24, 32, 24, 24)
        }

        qrImage = ImageView(this).apply {
            adjustViewBounds = true
            visibility = View.GONE
        }

        remoteRenderer = SurfaceViewRenderer(this).apply {
            init(eglBase!!.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setMirror(false)
            visibility = View.GONE
        }

        val cameraButton = Button(this).apply {
            text = "Gunakan sebagai Mode Kamera"
            setOnClickListener {
                requestCameraPermissions()
            }
        }

        val viewerButton = Button(this).apply {
            text = "Gunakan sebagai Mode Pemantau"
            setOnClickListener {
                val options = ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Arahkan kamera ke QR Code MonView")
                    setBeepEnabled(false)
                    setOrientationLocked(true)
                }
                scannerLauncher.launch(options)
            }
        }

        val stopButton = Button(this).apply {
            text = "Hentikan layanan"
            setOnClickListener {
                stopCurrentSession()
                status.text = "Koneksi MonView dihentikan."
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 24, 24, 24)

            addView(status)
            addView(qrImage)

            addView(
                remoteRenderer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    560
                )
            )

            addView(cameraButton)
            addView(viewerButton)
            addView(stopButton)
        }

        setContentView(layout)
    }

    private fun requestCameraPermissions() {
        val needed = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) !=
                PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            createCameraSession()
        }
    }

    private fun createCameraSession() {
        stopCurrentSession()

        status.text = "Menghubungkan ke Firebase..."

        FirebaseManager.login { success, message ->
            runOnUiThread {
                if (!success) {
                    status.text = "Gagal masuk Firebase: $message"
                    return@runOnUiThread
                }

                SessionManager.createSession { created, result ->
                    runOnUiThread {
                        if (!created) {
                            status.text = "Gagal membuat sesi: $result"
                            return@runOnUiThread
                        }

                        try {
                            activeCode = result

                            qrImage.setImageBitmap(
                                QrCodeGenerator.generate(result)
                            )
                            qrImage.visibility = View.VISIBLE

                            status.text =
                                "QR siap dipindai HP pemantau.\n" +
                                "Berlaku 60 detik.\n" +
                                "Kamera aktif setelah HP pemantau bergabung."

                            viewerListener =
                                SessionManager.listenForViewer(result) {
                                        joined, info ->

                                    runOnUiThread {
                                        if (joined && !startingCamera) {
                                            startingCamera = true
                                            startCameraConnection(result)
                                        } else if (!joined) {
                                            status.text =
                                                "Gagal mendeteksi pemantau: $info"
                                        }
                                    }
                                }

                        } catch (e: Exception) {
                            status.text =
                                "QR gagal dibuat: ${e.message}"
                        }
                    }
                }
            }
        }
    }

    private fun startCameraConnection(code: String) {
        status.text = "Pemantau bergabung. Menyalakan kamera..."

        val stream = CameraStream(this)

        if (!stream.start()) {
            status.text =
                "Kamera atau mikrofon gagal dinyalakan."
            startingCamera = false
            return
        }

        cameraStream = stream

        webRtcSession = WebRtcSession(
            code = code,
            isCamera = true,
            onRemoteTrack = {},
            onStatus = { message ->
                runOnUiThread {
                    status.text = "Mode kamera: $message"
                }
            }
        )

        webRtcSession?.startCamera(stream)

        status.text =
            "Kamera aktif. Menghubungkan ke HP pemantau..."
    }

    private fun joinCameraSession(code: String) {
        stopCurrentSession()

        status.text = "Memeriksa kode QR..."

        SessionManager.joinSession(code) { success, message ->
            runOnUiThread {
                if (!success) {
                    status.text = "GAGAL BERGABUNG\n\n$message"
                    return@runOnUiThread
                }

                activeCode = code
                remoteRenderer.visibility = View.VISIBLE

                status.text =
                    "Berhasil bergabung. Menunggu video kamera..."

                try {
                    WebRtcManager.initialize(this)

                    webRtcSession = WebRtcSession(
                        code = code,
                        isCamera = false,
                        onRemoteTrack = { track: VideoTrack ->
                            runOnUiThread {
                                track.addSink(remoteRenderer)
                                status.text =
                                    "Video MonView diterima."
                            }
                        },
                        onStatus = { messageText ->
                            runOnUiThread {
                                status.text =
                                    "Mode pemantau: $messageText"
                            }
                        }
                    )

                    webRtcSession?.startViewer()

                } catch (e: Exception) {
                    status.text =
                        "Gagal memulai pemantau: ${e.message}"
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

        webRtcSession?.stop()
        webRtcSession = null

        cameraStream?.stop()
        cameraStream = null

        activeCode = null
        startingCamera = false

        if (::remoteRenderer.isInitialized) {
            remoteRenderer.clearImage()
            remoteRenderer.visibility = View.GONE
        }

        if (::qrImage.isInitialized) {
            qrImage.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        stopCurrentSession()

        if (::remoteRenderer.isInitialized) {
            remoteRenderer.release()
        }

        eglBase?.release()
        eglBase = null

        super.onDestroy()
    }
}
