package com.monview

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

    private val backgroundColor = Color.rgb(8, 12, 18)
    private val panelColor = Color.rgb(22, 29, 39)
    private val accentColor = Color.rgb(0, 190, 150)
    private val whiteColor = Color.WHITE
    private val mutedColor = Color.rgb(155, 166, 180)

    private lateinit var rootFrame: FrameLayout
    private lateinit var homeScreen: LinearLayout
    private lateinit var cameraScreen: LinearLayout
    private lateinit var viewerScreen: FrameLayout

    // Status kamera dan pemantau dipisahkan agar tidak saling menimpa.
    private lateinit var cameraStatus: TextView
    private lateinit var viewerStatus: TextView

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
                Toast.makeText(
                    this,
                    "Pemindaian QR dibatalkan.",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                joinCameraSession(code.trim())
            }
        }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            if (
                results.isNotEmpty() &&
                results.values.all { it }
            ) {
                createCameraSession()
            } else {
                Toast.makeText(
                    this,
                    "Izin kamera dan mikrofon diperlukan.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        eglBase = EglBase.create()

        buildHomeScreen()
        buildCameraScreen()
        buildViewerScreen()

        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(backgroundColor)
            addView(homeScreen, matchParent())
            addView(cameraScreen, matchParent())
            addView(viewerScreen, matchParent())
        }

        showHomeScreen()
        setContentView(rootFrame)
    }

    private fun buildHomeScreen() {
        homeScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(35), dp(24), dp(24))
            setBackgroundColor(backgroundColor)
        }

        val logo = makeText(
            "MONVIEW",
            30f,
            accentColor,
            true
        ).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
        }

        val subtitle = makeText(
            "PEMANTAU KAMERA JARAK JAUH",
            11f,
            mutedColor,
            false
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(35))
        }

        val cameraButton = makeButton(
            "📷   MULAI SEBAGAI KAMERA",
            accentColor
        ) {
            requestCameraPermissions()
        }

        val viewerButton = makeButton(
            "▶   SAMBUNG SEBAGAI PEMANTAU",
            panelColor
        ) {
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Pindai QR MonView")
                setBeepEnabled(false)
                setOrientationLocked(true)
            }

            scannerLauncher.launch(options)
        }

        val info = makeText(
            "Pilih peran perangkat untuk memulai koneksi.",
            13f,
            mutedColor,
            false
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(25), dp(12), 0)
        }

        homeScreen.addView(logo, fullWidth())
        homeScreen.addView(subtitle, fullWidth())
        homeScreen.addView(cameraButton, fullWidth())
        homeScreen.addView(viewerButton, fullWidth())
        homeScreen.addView(info, fullWidth())
    }

    private fun buildCameraScreen() {
        cameraScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(28), dp(22), dp(22))
            setBackgroundColor(backgroundColor)
            visibility = View.GONE
        }

        val title = makeText(
            "MONVIEW CAMERA",
            23f,
            accentColor,
            true
        ).apply {
            gravity = Gravity.CENTER
        }

        cameraStatus = makeText(
            "MENYIAPKAN KAMERA...",
            14f,
            whiteColor,
            true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(20), dp(12), dp(20))
        }

        qrImage = ImageView(this).apply {
            visibility = View.GONE
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val qrPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedBackground(panelColor, 18)

            addView(
                qrImage,
                LinearLayout.LayoutParams(dp(230), dp(230))
            )

            addView(
                makeText(
                    "PINDAI QR INI DARI HP PEMANTAU",
                    12f,
                    mutedColor,
                    true
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(12), 0, 0)
                },
                fullWidth()
            )
        }

        val stopButton = makeButton(
            "HENTIKAN KAMERA",
            Color.rgb(150, 45, 55)
        ) {
            stopCurrentSession()
            showHomeScreen()
        }

        val backButton = makeButton(
            "KEMBALI",
            panelColor
        ) {
            showHomeScreen()
        }

        cameraScreen.addView(title, fullWidth())
        cameraScreen.addView(cameraStatus, fullWidth())
        cameraScreen.addView(qrPanel, fullWidth())
        cameraScreen.addView(stopButton, fullWidth())
        cameraScreen.addView(backButton, fullWidth())
    }

    private fun buildViewerScreen() {
        viewerScreen = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        remoteRenderer = SurfaceViewRenderer(this).apply {
            init(eglBase!!.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setMirror(false)
            setBackgroundColor(Color.BLACK)
        }

        nightVisionSink = NightVisionVideoSink(remoteRenderer)

        viewerScreen.addView(
            remoteRenderer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.rgb(5, 8, 12),
                    Color.TRANSPARENT
                )
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logo = makeText(
            "MONVIEW",
            20f,
            whiteColor,
            true
        )

        val connected = makeText(
            "● TERHUBUNG",
            12f,
            accentColor,
            true
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            logo,
            LinearLayout.LayoutParams(0, dp(36), 1f)
        )

        header.addView(connected)

        viewerStatus = makeText(
            "MENGHUBUNGKAN KE KAMERA...",
            12f,
            whiteColor,
            true
        ).apply {
            setPadding(0, dp(8), 0, 0)
        }

        topPanel.addView(header, fullWidth())
        topPanel.addView(viewerStatus, fullWidth())

        viewerScreen.addView(
            topPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(25), dp(12), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(
                    Color.rgb(5, 8, 12),
                    Color.TRANSPARENT
                )
            )
        }

        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        audioButton = makeControlButton("SUARA AKTIF") {
            audioEnabled = !audioEnabled

            remoteAudioTrack?.setEnabled(audioEnabled)

            audioButton.text = if (audioEnabled) {
                "SUARA AKTIF"
            } else {
                "SUARA MATI"
            }
        }

        switchCameraButton = makeControlButton("GANTI KAMERA") {
            val code = activeCode ?: return@makeControlButton

            WebRtcSignaling.requestCameraSwitch(code) {
                    success, message ->

                runOnUiThread {
                    viewerStatus.text = if (success) {
                        "PERINTAH GANTI KAMERA TERKIRIM"
                    } else {
                        "GAGAL: $message"
                    }
                }
            }
        }

        firstRow.addView(
            audioButton,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(6)
            }
        )

        firstRow.addView(
            switchCameraButton,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(6)
            }
        )

        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }

        nightVisionButton = makeControlButton("NIGHT VISION MATI") {
            nightVisionEnabled = !nightVisionEnabled

            nightVisionSink?.setNightVisionEnabled(nightVisionEnabled)

            nightVisionButton.text = if (nightVisionEnabled) {
                "NIGHT VISION AKTIF"
            } else {
                "NIGHT VISION MATI"
            }
        }

        val recordButton = makeControlButton("REKAM") {
            viewerStatus.text = "FITUR REKAM BELUM TERSEDIA"
        }.apply {
            isEnabled = false
            alpha = 0.45f
        }

        val photoButton = makeControlButton("FOTO") {
            viewerStatus.text = "FITUR FOTO BELUM TERSEDIA"
        }.apply {
            isEnabled = false
            alpha = 0.45f
        }

        secondRow.addView(
            nightVisionButton,
            LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginEnd = dp(4)
            }
        )

        secondRow.addView(
            recordButton,
            LinearLayout.LayoutParams(0, dp(46), 0.65f).apply {
                marginHorizontalCompat(dp(4))
            }
        )

        secondRow.addView(
            photoButton,
            LinearLayout.LayoutParams(0, dp(46), 0.65f).apply {
                marginStart = dp(4)
            }
        )

        val thirdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }

        val fullscreenButton = makeControlButton("LAYAR PENUH") {
            toggleFullscreen()
        }

        val lockButton = makeControlButton("KUNCI LAYAR") {
            viewerStatus.text = "KUNCI SENTUH BELUM TERSEDIA"
        }.apply {
            isEnabled = false
            alpha = 0.45f
        }

        thirdRow.addView(
            fullscreenButton,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginEnd = dp(5)
            }
        )

        thirdRow.addView(
            lockButton,
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(5)
            }
        )

        // Tombol ini wajib memberikan action saat dibuat.
        val stopButton = makeControlButton("PUTUSKAN KONEKSI") {
            stopCurrentSession()
            showHomeScreen()
        }.apply {
            setTextColor(Color.rgb(255, 110, 110))
        }

        bottomPanel.addView(firstRow, fullWidth())
        bottomPanel.addView(secondRow, fullWidth())
        bottomPanel.addView(thirdRow, fullWidth())

        bottomPanel.addView(
            stopButton,
            fullWidth().apply {
                topMargin = dp(10)
            }
        )

        viewerScreen.addView(
            bottomPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
    }

    private fun LinearLayout.LayoutParams.marginHorizontalCompat(
        value: Int
    ) {
        marginStart = value
        marginEnd = value
    }

    private fun showHomeScreen() {
        homeScreen.visibility = View.VISIBLE
        cameraScreen.visibility = View.GONE
        viewerScreen.visibility = View.GONE
        window.decorView.systemUiVisibility = 0
    }

    private fun showCameraScreen() {
        homeScreen.visibility = View.GONE
        cameraScreen.visibility = View.VISIBLE
        viewerScreen.visibility = View.GONE
    }

    private fun showViewerScreen() {
        homeScreen.visibility = View.GONE
        cameraScreen.visibility = View.GONE
        viewerScreen.visibility = View.VISIBLE
    }

    private fun toggleFullscreen() {
        val current = window.decorView.systemUiVisibility

        window.decorView.systemUiVisibility =
            if (current and View.SYSTEM_UI_FLAG_FULLSCREEN != 0) {
                0
            } else {
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
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
        showCameraScreen()

        cameraStatus.text = "MEMBUAT QR..."

        SessionManager.createSession { success, result ->
            runOnUiThread {
                if (!success) {
                    cameraStatus.text = "GAGAL MEMBUAT QR\n$result"
                    return@runOnUiThread
                }

                activeCode = result

                qrImage.setImageBitmap(
                    QrCodeGenerator.generate(result)
                )

                qrImage.visibility = View.VISIBLE

                cameraStatus.text =
                    "MENUNGGU HP PEMANTAU\nQR berlaku 5 menit."

                viewerListener =
                    SessionManager.listenForViewer(
                        result,
                        this
                    ) { joined, message ->

                        runOnUiThread {
                            if (joined && !startingCamera) {
                                startingCamera = true
                                startCameraConnection(result)
                            } else if (!joined) {
                                cameraStatus.text = message
                            }
                        }
                    }
            }
        }
    }

    private fun startCameraConnection(code: String) {
        cameraStatus.text = "MENYIAPKAN LAYANAN KAMERA..."

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

            cameraStatus.text =
                "KAMERA AKTIF\nLayanan latar belakang dijalankan."

        } catch (e: Exception) {
            startingCamera = false
            cameraServiceStarted = false

            cameraStatus.text =
                "GAGAL MENJALANKAN LAYANAN\n${e.message}"
        }
    }

    private fun joinCameraSession(code: String) {
        stopCurrentSession()
        showViewerScreen()

        viewerStatus.text = "MEMERIKSA QR..."

        SessionManager.joinSession(code, this) { success, message ->
            runOnUiThread {
                if (!success) {
                    viewerStatus.text = "GAGAL BERGABUNG\n$message"
                    showHomeScreen()
                    return@runOnUiThread
                }

                activeCode = code

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

                                viewerStatus.text = "VIDEO DITERIMA"
                            }
                        },

                        onStatus = { messageText ->
                            runOnUiThread {
                                viewerStatus.text = messageText
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

                    viewerStatus.text = "TERHUBUNG\nMenunggu video..."

                } catch (e: Exception) {
                    viewerStatus.text =
                        "GAGAL MEMULAI\n${e.message}"
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

        nightVisionButton.text = "NIGHT VISION MATI"
        audioButton.text = "SUARA AKTIF"
    }

    private fun makeText(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)

        if (bold) {
            setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun makeButton(
        label: String,
        color: Int,
        action: () -> Unit
    ) = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(whiteColor)
        background = roundedBackground(color, 14)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setOnClickListener {
            action()
        }

        val params = fullWidth()
        params.topMargin = dp(10)
        layoutParams = params
    }

    private fun makeControlButton(
        label: String,
        action: () -> Unit
    ) = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        setTextColor(whiteColor)

        background = roundedBackground(
            Color.rgb(35, 44, 55),
            12
        )

        setPadding(dp(5), dp(5), dp(5), dp(5))

        setOnClickListener {
            action()
        }
    }

    private fun roundedBackground(
        color: Int,
        radius: Int
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun matchParent() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
    )

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
        ).toInt()
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

        // Layanan kamera tidak dihentikan saat Activity ditutup.

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