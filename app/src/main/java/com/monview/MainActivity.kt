
package com.monview

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
    private val panel = Color.rgb(25, 25, 25)

    private lateinit var status: TextView
    private lateinit var qrImage: ImageView
    private lateinit var remoteRenderer: SurfaceViewRenderer
    private lateinit var switchCameraButton: Button
    private lateinit var nightVisionButton: Button
    private lateinit var audioButton: Button

    private var nightVisionSink: NightVisionVideoSink? = null
    private var nightVisionEnabled = false

    private var remoteAudioTrack: AudioTrack? = null
    private var audioEnabled = true

    private var eglBase: EglBase? = null
    private var cameraStream: CameraStream? = null
    private var webRtcSession: WebRtcSession? = null
    private var viewerListener: ValueEventListener? = null
    private var cameraSwitchListener: ValueEventListener? = null
    private var activeCode: String? = null
    private var startingCamera = false
    private var lastCameraSwitchRequest = 0L

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
            if (results.isNotEmpty() && results.values.all { it }) {
                createCameraSession()
            } else {
                status.text = "Izin kamera dan mikrofon belum lengkap."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = dark
        window.navigationBarColor = dark
        window.decorView.setBackgroundColor(dark)
        eglBase = EglBase.create()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(22), dp(20), dp(20))
            setBackgroundColor(dark)
        }

        val brand = TextView(this).apply {
            text = "M W"
            textSize = 34f
            letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(gold)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "MONVIEW  •  PRIVATE LIVE MONITOR"
            textSize = 10f
            letterSpacing = 0.16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }

        status = TextView(this).apply {
            text = "PILIH PERANGKAT\n\nPilih mode kamera atau pemantau."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(panel, 14, Color.rgb(55, 48, 28), 1)
        }

        qrImage = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.WHITE, 12)
        }

        remoteRenderer = SurfaceViewRenderer(this).apply {
            init(eglBase!!.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setMirror(false)
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }

        nightVisionSink = NightVisionVideoSink(remoteRenderer)

        val videoLabel = TextView(this).apply {
            text = "LIVE VIEW"
            textSize = 11f
            letterSpacing = 0.14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(gold)
            setPadding(2, dp(14), 2, dp(8))
        }

        val cameraButton = makeButton("MULAI SEBAGAI KAMERA", true) {
            requestCameraPermissions()
        }

        val viewerButton = makeButton("SAMBUNG SEBAGAI PEMANTAU", false) {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Arahkan kamera ke QR Code MonView")
                setBeepEnabled(false)
                setOrientationLocked(true)
            }
            scannerLauncher.launch(options)
        }

        switchCameraButton =
            makeButton("GANTI KAMERA DEPAN / BELAKANG", true) {
                val code = activeCode ?: return@makeButton
                switchCameraButton.isEnabled = false

                WebRtcSignaling.requestCameraSwitch(code) { success, message ->
                    runOnUiThread {
                        switchCameraButton.isEnabled = true
                        status.text = if (success) {
                            "PERINTAH TERKIRIM\nHP kamera sedang mengganti posisi kamera."
                        } else {
                            "GAGAL MENGIRIM PERINTAH\n$message"
                        }
                    }
                }
            }.apply {
                visibility = View.GONE
            }

        nightVisionButton =
            makeButton("NIGHT VISION: MATI", false) {
                nightVisionEnabled = !nightVisionEnabled
                nightVisionSink?.setNightVisionEnabled(nightVisionEnabled)

                nightVisionButton.text =
                    if (nightVisionEnabled) {
                        "NIGHT VISION: AKTIF"
                    } else {
                        "NIGHT VISION: MATI"
                    }

                status.text =
                    if (nightVisionEnabled) {
                        "NIGHT VISION AKTIF\nVideo hitam putih dengan peningkatan kontras."
                    } else {
                        "NIGHT VISION NONAKTIF\nTampilan video kembali normal."
                    }
            }.apply {
                visibility = View.GONE
            }

        audioButton =
            makeButton("SUARA HP: AKTIF", false) {
                audioEnabled = !audioEnabled
                remoteAudioTrack?.setEnabled(audioEnabled)

                audioButton.text =
                    if (audioEnabled) {
                        "SUARA HP: AKTIF"
                    } else {
                        "SENYAP: AKTIF"
                    }

                status.text =
                    if (audioEnabled) {
                        "AUDIO AKTIF\nMendengarkan suara dari mikrofon HP kamera."
                    } else {
                        "MODE SENYAP AKTIF\nSuara dimatikan di HP pemantau."
                    }
            }.apply {
                visibility = View.GONE
            }

        val stopButton = makeButton("HENTIKAN KONEKSI", false) {
            stopCurrentSession()
            status.text = "KONEKSI DIHENTIKAN"
        }

        root.addView(brand, matchWrap())
        root.addView(subtitle, matchWrap())
        root.addView(status, matchWrap().apply {
            topMargin = dp(18)
            bottomMargin = dp(12)
        })
        root.addView(qrImage, LinearLayout.LayoutParams(dp(220), dp(220)).apply {
            gravity = Gravity.CENTER
            bottomMargin = dp(12)
        })
        root.addView(videoLabel, matchWrap())
        root.addView(remoteRenderer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(360)
        ).apply {
            bottomMargin = dp(14)
        })
        root.addView(cameraButton, matchWrap().apply { bottomMargin = dp(10) })
        root.addView(viewerButton, matchWrap().apply { bottomMargin = dp(10) })
        root.addView(switchCameraButton, matchWrap().apply { bottomMargin = dp(10) })
        root.addView(nightVisionButton, matchWrap().apply { bottomMargin = dp(10) })
        root.addView(audioButton, matchWrap().apply { bottomMargin = dp(10) })
        root.addView(stopButton, matchWrap())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(dark)
            addView(root)
        }
        setContentView(scroll)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun rounded(
        color: Int,
        radius: Int,
        strokeColor: Int? = null,
        strokeWidth: Int = 0
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (strokeColor != null && strokeWidth > 0) {
            setStroke(dp(strokeWidth), strokeColor)
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun makeButton(
        label: String,
        primary: Boolean,
        action: () -> Unit
    ): Button = Button(this).apply {
        text = label
        textSize = 12f
        letterSpacing = 0.04f
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        setTextColor(if (primary) Color.BLACK else gold)
        background = rounded(
            if (primary) gold else panel,
            12,
            gold,
            1
        )
        minHeight = dp(52)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { action() }
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
        status.text = "MENGHUBUNGKAN KE FIREBASE..."

        FirebaseManager.login { success, message ->
            runOnUiThread {
                if (!success) {
                    status.text = "LOGIN FIREBASE GAGAL\n$message"
                    return@runOnUiThread
                }

                SessionManager.createSession { created, result ->
                    runOnUiThread {
                        if (!created) {
                            status.text = "GAGAL MEMBUAT SESI\n$result"
                            return@runOnUiThread
                        }
                        try {
                            activeCode = result
                            qrImage.setImageBitmap(QrCodeGenerator.generate(result))
                            qrImage.visibility = View.VISIBLE
                            status.text = "MENUNGGU HP PEMANTAU\nQR berlaku selama 60 detik."

                            viewerListener =
                                SessionManager.listenForViewer(result) { joined, info ->
                                    runOnUiThread {
                                        if (joined && !startingCamera) {
                                            startingCamera = true
                                            startCameraConnection(result)
                                        } else if (!joined) {
                                            status.text =
                                                "GAGAL MENDETEKSI PEMANTAU\n$info"
                                        }
                                    }
                                }
                        } catch (e: Exception) {
                            status.text = "QR GAGAL DIBUAT\n${e.message}"
                        }
                    }
                }
            }
        }
    }

    private fun startCameraConnection(code: String) {
        status.text = "PEMANTAU TERHUBUNG\nMenyalakan kamera..."
        val stream = CameraStream(this)
        if (!stream.start()) {
            status.text = "KAMERA ATAU MIKROFON GAGAL DINYALAKAN"
            startingCamera = false
            return
        }
        cameraStream = stream
        lastCameraSwitchRequest = 0L

        cameraSwitchListener =
            WebRtcSignaling.listenCameraSwitch(code) { requestAt ->
                if (requestAt > lastCameraSwitchRequest) {
                    lastCameraSwitchRequest = requestAt
                    runOnUiThread {
                        cameraStream?.switchCamera { success, message ->
                            runOnUiThread {
                                status.text = if (success) {
                                    "KAMERA BERHASIL DIGANTI\n$message"
                                } else {
                                    "GAGAL MENGGANTI KAMERA\n$message"
                                }
                            }
                        }
                    }
                }
            }

        webRtcSession = WebRtcSession(
            code = code,
            isCamera = true,
            onRemoteTrack = {},
            onStatus = { message ->
                runOnUiThread { status.text = "MODE KAMERA\n$message" }
            }
        )
        webRtcSession?.startCamera(stream)
        status.text = "KAMERA AKTIF\nMenghubungkan ke HP pemantau..."
    }

    private fun joinCameraSession(code: String) {
        stopCurrentSession()
        status.text = "MEMERIKSA KODE QR..."

        SessionManager.joinSession(code) { success, message ->
            runOnUiThread {
                if (!success) {
                    status.text = "GAGAL BERGABUNG\n$message"
                    return@runOnUiThread
                }

                activeCode = code

                nightVisionEnabled = false
                nightVisionSink?.setNightVisionEnabled(false)
                nightVisionButton.text = "NIGHT VISION: MATI"
                nightVisionButton.visibility = View.VISIBLE

                audioEnabled = true
                remoteAudioTrack = null
                audioButton.text = "SUARA HP: AKTIF"
                audioButton.visibility = View.VISIBLE

                remoteRenderer.visibility = View.VISIBLE
                switchCameraButton.visibility = View.VISIBLE
                status.text = "TERHUBUNG\nMenunggu video dan audio kamera..."

                try {
                    WebRtcManager.initialize(this)
                    webRtcSession = WebRtcSession(
                        code = code,
                        isCamera = false,
                        onRemoteTrack = { track: VideoTrack ->
                            runOnUiThread {
                                nightVisionSink?.let { sink ->
                                    track.addSink(sink)
                                }
                                status.text = "LIVE\nVideo MonView diterima."
                            }
                        },
                        onStatus = { messageText ->
                            runOnUiThread {
                                status.text = "STATUS KONEKSI\n$messageText"
                            }
                        },
                        onRemoteAudioTrack = { track: AudioTrack ->
                            runOnUiThread {
                                remoteAudioTrack = track
                                track.setEnabled(audioEnabled)
                                status.text = if (audioEnabled) {
                                    "AUDIO TERHUBUNG\nSuara HP kamera siap didengarkan."
                                } else {
                                    "MODE SENYAP AKTIF"
                                }
                            }
                        }
                    )
                    webRtcSession?.startViewer()
                } catch (e: Exception) {
                    status.text = "GAGAL MEMULAI PEMANTAU\n${e.message}"
                }
            }
        }
    }

    private fun stopCurrentSession() {
        val code = activeCode

        if (code != null && viewerListener != null) {
            SessionManager.removeViewerListener(code, viewerListener!!)
        }
        viewerListener = null

        if (code != null && cameraSwitchListener != null) {
            WebRtcSignaling.removeCameraSwitchListener(
                code, cameraSwitchListener!!
            )
        }
        cameraSwitchListener = null

        remoteAudioTrack?.setEnabled(false)
        remoteAudioTrack = null
        audioEnabled = true

        webRtcSession?.stop()
        webRtcSession = null
        cameraStream?.stop()
        cameraStream = null

        activeCode = null
        startingCamera = false
        lastCameraSwitchRequest = 0L

        nightVisionEnabled = false
        nightVisionSink?.setNightVisionEnabled(false)

        if (::nightVisionButton.isInitialized) {
            nightVisionButton.text = "NIGHT VISION: MATI"
            nightVisionButton.visibility = View.GONE
        }

        if (::audioButton.isInitialized) {
            audioButton.text = "SUARA HP: AKTIF"
            audioButton.visibility = View.GONE
        }

        if (::switchCameraButton.isInitialized) {
            switchCameraButton.visibility = View.GONE
            switchCameraButton.isEnabled = true
        }
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
        nightVisionSink = null

        if (::remoteRenderer.isInitialized) {
            remoteRenderer.release()
        }
        eglBase?.release()
        eglBase = null
        super.onDestroy()
    }
}
