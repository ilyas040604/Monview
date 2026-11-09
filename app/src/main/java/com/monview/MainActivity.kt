package com.monview

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import org.webrtc.RendererCommon
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

    private lateinit var cameraStatus: TextView
    private lateinit var viewerStatus: TextView
    private lateinit var viewerVideoStatus: TextView
    private lateinit var viewerConnectionLabel: TextView
    private lateinit var qrImage: ImageView
    private lateinit var remoteRenderer: SurfaceViewRenderer

    private lateinit var switchCameraButton: Button
    private lateinit var nightVisionButton: Button
    private lateinit var audioButton: Button
    private lateinit var touchLockOverlay: TextView
    private lateinit var lockButton: Button
    private lateinit var recordButton: Button

    private var touchLocked = false
    private var recordingActive = false
    private var recordingReceiverRegistered = false

    private var webRtcSession: WebRtcSession? = null
    private var viewerListener: ValueEventListener? = null
    private var cameraStatusListener: ValueEventListener? = null
    private var nightVisionSink: NightVisionVideoSink? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null

    private var activeCode: String? = null
    private var activeAttemptId: String? = null
    private var sessionGeneration = 0L
    private var connectionTimeoutRunnable: Runnable? = null
    private var startingCamera = false
    private var cameraServiceStarted = false
    private var nightVisionEnabled = false
    private var audioEnabled = true
    private var reconnectAttempts = 0
    private var reconnectRunnable: Runnable? = null

    private val reconnectHandler = Handler(Looper.getMainLooper())

    private var previousAudioMode: Int? = null
    private var previousSpeakerphoneState: Boolean? = null
    private var previousCommunicationDevice: AudioDeviceInfo? = null
    private var audioRouteCaptured = false

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
                joinCameraSession(code.trim(), restoring = false)
            }
        }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            if (results.isNotEmpty() && results.values.all { it }) {
                createCameraSession()
            } else {
                Toast.makeText(
                    this,
                    "Izin kamera dan mikrofon diperlukan.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val photoPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                capturePhotoNow()
            } else {
                Toast.makeText(
                    this,
                    "Izin penyimpanan diperlukan untuk menyimpan foto.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val screenCaptureLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (
                result.resultCode == Activity.RESULT_OK &&
                result.data != null
            ) {
                try {
                    val serviceIntent = Intent(
                        this,
                        ScreenRecordingService::class.java
                    ).apply {
                        action = ScreenRecordingService.ACTION_START

                        putExtra(
                            ScreenRecordingService.EXTRA_RESULT_CODE,
                            result.resultCode
                        )

                        putExtra(
                            ScreenRecordingService.EXTRA_RESULT_DATA,
                            result.data
                        )
                    }

                    ContextCompat.startForegroundService(
                        this,
                        serviceIntent
                    )

                    viewerStatus.text = "MENYIAPKAN REKAMAN VIDEO..."

                } catch (e: Exception) {
                    updateRecordingUi(
                        false,
                        "GAGAL MEMULAI REKAMAN: ${e.message}"
                    )
                }

            } else {
                viewerStatus.text = "IZIN REKAM DIBATALKAN"
                Toast.makeText(
                    this,
                    "Izin perekaman layar tidak diberikan.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val recordingStateReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                if (intent?.action != ScreenRecordingService.ACTION_STATE) {
                    return
                }

                val active = intent.getBooleanExtra(
                    ScreenRecordingService.EXTRA_IS_RECORDING,
                    false
                )

                val message = intent.getStringExtra(
                    ScreenRecordingService.EXTRA_MESSAGE
                )

                updateRecordingUi(active, message)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor

        WebRtcManager.initialize(applicationContext)

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

        restoreSavedPairing()
    }

    override fun onStart() {
        super.onStart()

        ContextCompat.registerReceiver(
            this,
            recordingStateReceiver,
            IntentFilter(ScreenRecordingService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        recordingReceiverRegistered = true

        if (::recordButton.isInitialized) {
            updateRecordingUi(
                ScreenRecordingService.isRecording,
                null
            )
        }
    }

    override fun onStop() {
        if (recordingReceiverRegistered) {
            try {
                unregisterReceiver(recordingStateReceiver)
            } catch (_: Exception) {
            }

            recordingReceiverRegistered = false
        }

        super.onStop()
    }

    private fun updateRecordingUi(
        active: Boolean,
        message: String?
    ) {
        recordingActive = active

        if (::recordButton.isInitialized) {
            recordButton.text =
                if (active) "STOP REKAM" else "REKAM"

            recordButton.background = roundedBackground(
                if (active) {
                    Color.rgb(160, 42, 52)
                } else {
                    Color.rgb(35, 44, 55)
                },
                12
            )
        }

        if (!message.isNullOrBlank() && ::viewerStatus.isInitialized) {
            viewerStatus.text = message
        }
    }

    private fun toggleVideoRecording() {
        if (recordingActive || ScreenRecordingService.isRecording) {
            stopVideoRecording()
            return
        }

        if (remoteVideoTrack == null || nightVisionSink == null) {
            Toast.makeText(
                this,
                "Video kamera belum diterima.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        try {
            screenCaptureLauncher.launch(
                manager.createScreenCaptureIntent()
            )
        } catch (e: Exception) {
            viewerStatus.text =
                "GAGAL MEMBUKA IZIN REKAM: ${e.message}"
        }
    }

    private fun stopVideoRecording() {
        val intent = Intent(
            this,
            ScreenRecordingService::class.java
        ).apply {
            action = ScreenRecordingService.ACTION_STOP
        }

        try {
            startService(intent)
            viewerStatus.text = "MENGHENTIKAN REKAMAN..."
        } catch (e: Exception) {
            viewerStatus.text =
                "GAGAL MENGHENTIKAN REKAMAN: ${e.message}"
        }
    }

    private fun restoreSavedPairing() {
        val pairing = PairingStore.get(this) ?: return
        activeCode = pairing.pairId

        when (pairing.role) {
            "camera" -> {
                showCameraScreen()
                qrImage.visibility = View.GONE
                cameraStatus.text = "MEMULIHKAN LAYANAN KAMERA..."
                startCameraStatusListener(pairing.pairId)
                startCameraConnection(pairing.pairId)
            }

            "viewer" -> {
                joinCameraSession(pairing.pairId, restoring = true)
            }

            else -> {
                PairingStore.clear(this)
                activeCode = null
            }
        }
    }

    private fun buildHomeScreen() {
        homeScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(35), dp(24), dp(24))
            setBackgroundColor(backgroundColor)
        }

        val logo = makeText("MONVIEW", 30f, accentColor, true).apply {
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
            stopCurrentSession(clearPairing = true)
            showHomeScreen()
        }

        val backButton = makeButton("KEMBALI", panelColor) {
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
            init(
                WebRtcManager.getEglBaseContext()
                    ?: throw IllegalStateException("EGL WebRTC belum siap"),

                object : RendererCommon.RendererEvents {

                    override fun onFirstFrameRendered() {
                        runOnUiThread {
                            if (::viewerVideoStatus.isInitialized) {
                                viewerVideoStatus.text =
                                    "VIDEO: FRAME TAMPIL DI LAYAR"
                                viewerVideoStatus.setTextColor(accentColor)
                            }
                        }
                    }

                    override fun onFrameResolutionChanged(
                        videoWidth: Int,
                        videoHeight: Int,
                        rotation: Int
                    ) {
                        runOnUiThread {
                            if (::viewerVideoStatus.isInitialized) {
                                viewerVideoStatus.text =
                                    "VIDEO: ${videoWidth}x${videoHeight} — ROTASI ${rotation}°"
                                viewerVideoStatus.setTextColor(accentColor)
                            }
                        }
                    }
                }
            )

            setEnableHardwareScaler(false)

            setScalingType(
                RendererCommon.ScalingType.SCALE_ASPECT_FIT
            )

            setMirror(false)
            setBackgroundColor(Color.BLACK)
        }

        viewerVideoStatus = makeText(
            "VIDEO: MENUNGGU TRACK",
            12f,
            Color.YELLOW,
            true
        )

        nightVisionSink = NightVisionVideoSink(remoteRenderer) {
            runOnUiThread {
                viewerVideoStatus.text =
                    "VIDEO: FRAME BERHASIL DITERIMA"
                viewerVideoStatus.setTextColor(accentColor)
            }
        }

        viewerScreen.addView(
            remoteRenderer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        touchLockOverlay = TextView(this).apply {
            text = "LAYAR TERKUNCI\nKETUK DI SINI UNTUK MEMBUKA"
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(Color.argb(210, 0, 0, 0))
            isClickable = true
            isFocusable = true
            visibility = View.GONE

            setOnClickListener {
                setTouchLocked(false)
            }
        }

        viewerScreen.addView(
            touchLockOverlay,
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

        val logo = makeText("MONVIEW", 20f, whiteColor, true)

        viewerConnectionLabel = makeText(
            "● MENYAMBUNG",
            12f,
            mutedColor,
            true
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            logo,
            LinearLayout.LayoutParams(0, dp(36), 1f)
        )

        header.addView(viewerConnectionLabel)

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
        topPanel.addView(viewerVideoStatus, fullWidth())

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

            audioButton.text =
                if (audioEnabled) "SUARA AKTIF" else "SUARA MATI"
        }

        switchCameraButton = makeControlButton("GANTI KAMERA") {
            val code = activeCode ?: return@makeControlButton

            WebRtcSignaling.requestCameraSwitch(code) { success, message ->
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

            nightVisionButton.text =
                if (nightVisionEnabled) {
                    "NIGHT VISION AKTIF"
                } else {
                    "NIGHT VISION MATI"
                }
        }

        recordButton = makeControlButton("REKAM") {
            toggleVideoRecording()
        }

        val photoButton = makeControlButton("FOTO") {
            capturePhoto()
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
                marginStart = dp(4)
                marginEnd = dp(4)
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

        lockButton = makeControlButton("KUNCI SENTUH") {
            setTouchLocked(!touchLocked)
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

        val stopButton = makeControlButton("PUTUSKAN KONEKSI") {
            stopCurrentSession(clearPairing = true)
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

    private fun setTouchLocked(locked: Boolean) {
        if (
            !::touchLockOverlay.isInitialized ||
            !::lockButton.isInitialized
        ) return

        touchLocked = locked

        touchLockOverlay.visibility =
            if (locked) View.VISIBLE else View.GONE

        lockButton.text =
            if (locked) "BUKA KUNCI" else "KUNCI SENTUH"

        if (::viewerStatus.isInitialized) {
            viewerStatus.text =
                if (locked) {
                    "LAYAR VIDEO DIKUNCI — TOMBOL KONTROL TETAP AKTIF"
                } else {
                    "KUNCI SENTUH DINONAKTIFKAN"
                }
        }
    }

    private fun capturePhoto() {
        if (remoteVideoTrack == null || nightVisionSink == null) {
            Toast.makeText(
                this,
                "Video kamera belum diterima. Tunggu sampai gambar tampil.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            photoPermissionLauncher.launch(
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            return
        }

        capturePhotoNow()
    }

    private fun capturePhotoNow() {
        val sink = nightVisionSink

        if (sink == null || remoteVideoTrack == null) {
            Toast.makeText(
                this,
                "Video kamera belum tersedia.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        viewerStatus.text = "MENGAMBIL FOTO..."

        val timeout = Runnable {
            sink.cancelPhotoRequest()
        }

        reconnectHandler.postDelayed(timeout, 5000L)

        sink.requestPhoto { bitmap ->
            reconnectHandler.removeCallbacks(timeout)

            runOnUiThread {
                if (bitmap == null) {
                    viewerStatus.text = "GAGAL MENGAMBIL FOTO"

                    Toast.makeText(
                        this,
                        "Frame foto tidak berhasil diproses.",
                        Toast.LENGTH_LONG
                    ).show()

                    return@runOnUiThread
                }

                val saved = try {
                    PhotoStorage.save(this, bitmap)
                } catch (_: Exception) {
                    false
                }

                bitmap.recycle()

                if (saved) {
                    viewerStatus.text =
                        "FOTO TERSIMPAN DI Pictures/MonView"

                    Toast.makeText(
                        this,
                        "Foto berhasil disimpan di galeri.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    viewerStatus.text = "GAGAL MENYIMPAN FOTO"

                    Toast.makeText(
                        this,
                        "Foto gagal disimpan. Periksa ruang penyimpanan.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun requestCameraPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(
                this,
                it
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            createCameraSession()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun createCameraSession() {
        stopCurrentSession(clearPairing = true)
        val generation = sessionGeneration

        showCameraScreen()
        cameraStatus.text = "MEMBUAT QR..."

        SessionManager.createSession { success, result ->
            runOnUiThread {
                if (
                    generation != sessionGeneration ||
                    isFinishing
                ) return@runOnUiThread

                if (!success) {
                    cameraStatus.text = "GAGAL MEMBUAT QR\n$result"
                    return@runOnUiThread
                }

                activeCode = result
                qrImage.setImageBitmap(QrCodeGenerator.generate(result))
                qrImage.visibility = View.VISIBLE

                cameraStatus.text =
                    "MENUNGGU HP PEMANTAU\nQR berlaku 5 menit."

                startCameraStatusListener(result)

                viewerListener =
                    SessionManager.listenForViewer(
                        result,
                        this
                    ) { joined, message ->

                        runOnUiThread {
                            if (
                                generation != sessionGeneration ||
                                activeCode != result ||
                                isFinishing
                            ) return@runOnUiThread

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

    private fun startCameraStatusListener(code: String) {
        cameraStatusListener?.let {
            WebRtcSignaling.removeCameraStatusListener(code, it)
        }

        cameraStatusListener =
            WebRtcSignaling.listenCameraStatus(code) { message ->

                runOnUiThread {
                    if (activeCode == code && !isFinishing) {
                        cameraStatus.text = message
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

        } catch (e: Exception) {
            startingCamera = false
            cameraServiceStarted = false
            cameraStatus.text =
                "GAGAL MENJALANKAN LAYANAN\n${e.message}"
        }
    }

    private fun joinCameraSession(
        code: String,
        restoring: Boolean
    ) {
        stopCurrentSession(clearPairing = !restoring)
        val generation = sessionGeneration

        activeCode = code
        showViewerScreen()

        viewerConnectionLabel.text = "● MEMERIKSA"
        viewerConnectionLabel.setTextColor(mutedColor)

        viewerStatus.text =
            if (restoring) {
                "MEMULIHKAN PAIRING..."
            } else {
                "MEMERIKSA QR..."
            }

        viewerVideoStatus.text = "VIDEO: MENUNGGU TRACK"
        viewerVideoStatus.setTextColor(Color.YELLOW)

        SessionManager.joinSession(code, this) { success, message ->

            runOnUiThread {
                if (
                    generation != sessionGeneration ||
                    activeCode != code ||
                    isFinishing
                ) return@runOnUiThread

                if (!success) {
                    viewerStatus.text = "GAGAL BERGABUNG\n$message"
                    viewerConnectionLabel.text = "● GAGAL"
                    viewerConnectionLabel.setTextColor(
                        Color.rgb(255, 110, 110)
                    )

                    if (
                        restoring &&
                        (
                            message.contains(
                                "tidak ditemukan",
                                ignoreCase = true
                            ) ||
                            message.contains(
                                "digunakan perangkat lain",
                                ignoreCase = true
                            ) ||
                            message.contains(
                                "pemilik QR tidak lengkap",
                                ignoreCase = true
                            ) ||
                            message.contains(
                                "masa pairing sudah berakhir",
                                ignoreCase = true
                            )
                        )
                    ) {
                        PairingStore.clear(this)
                    }

                    activeCode = null
                    showViewerScreen()
                    return@runOnUiThread
                }

                try {
                    WebRtcManager.initialize(this)
                    activateAudioOutput()
                    reconnectAttempts = 0
                    beginViewerAttempt(code, generation)

                } catch (e: Exception) {
                    viewerStatus.text = "GAGAL MEMULAI\n${e.message}"
                    viewerConnectionLabel.text = "● GAGAL"
                    viewerConnectionLabel.setTextColor(
                        Color.rgb(255, 110, 110)
                    )
                }
            }
        }
    }

    private fun beginViewerAttempt(
        code: String,
        generation: Long = sessionGeneration
    ) {
        if (
            generation != sessionGeneration ||
            activeCode != code ||
            isFinishing
        ) return

        clearViewerMedia()
        nightVisionSink?.resetFirstFrameReported()

        viewerConnectionLabel.text = "● MENYAMBUNG"
        viewerConnectionLabel.setTextColor(mutedColor)
        viewerStatus.text = "MEMINTA KONEKSI KAMERA..."
        viewerVideoStatus.text = "VIDEO: MENUNGGU TRACK"
        viewerVideoStatus.setTextColor(Color.YELLOW)

        WebRtcSignaling.requestConnection(code) {
                success,
                attemptId,
                message ->

            runOnUiThread {
                if (
                    generation != sessionGeneration ||
                    activeCode != code ||
                    isFinishing
                ) return@runOnUiThread

                if (!success) {
                    viewerStatus.text =
                        "PERMINTAAN KONEKSI GAGAL\n$message"

                    scheduleViewerReconnect(generation)
                    return@runOnUiThread
                }

                activeAttemptId = attemptId
                viewerStatus.text = "MENUNGGU MEDIA DARI KAMERA..."

                val session = WebRtcSession(
                    code = code,
                    attemptId = attemptId,
                    isCamera = false,

                    onRemoteTrack = { track: VideoTrack ->

                        runOnUiThread {
                            if (
                                generation != sessionGeneration ||
                                activeCode != code ||
                                activeAttemptId != attemptId ||
                                isFinishing
                            ) return@runOnUiThread

                            remoteVideoTrack?.let { old ->
                                nightVisionSink?.let {
                                    old.removeSink(it)
                                }
                            }

                            remoteVideoTrack = track

                            nightVisionSink?.let {
                                track.addSink(it)
                            }

                            viewerStatus.text =
                                "TRACK VIDEO DITERIMA — MENUNGGU KONEKSI MEDIA"

                            viewerVideoStatus.text =
                                "VIDEO: TRACK DITERIMA, MENUNGGU FRAME"

                            viewerVideoStatus.setTextColor(Color.YELLOW)
                        }
                    },

                    onStatus = { messageText ->

                        runOnUiThread {
                            if (
                                generation != sessionGeneration ||
                                activeCode != code ||
                                activeAttemptId != attemptId ||
                                isFinishing
                            ) return@runOnUiThread

                            viewerStatus.text = messageText

                            when {
                                messageText.contains(
                                    "WEBRTC: CONNECTED"
                                ) -> {
                                    viewerConnectionLabel.text =
                                        "● TERHUBUNG"

                                    viewerConnectionLabel.setTextColor(
                                        accentColor
                                    )

                                    reconnectAttempts = 0
                                    clearReconnectCallback()
                                    clearConnectionTimeout()
                                }

                                messageText.contains("ICE: FAILED") ||
                                messageText.contains("WEBRTC: FAILED") ||
                                messageText.contains("WEBRTC: DISCONNECTED") ||
                                messageText.contains("ICE: DISCONNECTED") ||
                                messageText.contains(
                                    "GAGAL MEMBUAT PEER CONNECTION"
                                ) -> {

                                    viewerConnectionLabel.text =
                                        "● TERPUTUS"

                                    viewerConnectionLabel.setTextColor(
                                        Color.rgb(255, 110, 110)
                                    )

                                    clearConnectionTimeout()
                                    scheduleViewerReconnect(generation)
                                }
                            }
                        }
                    },

                    onRemoteAudioTrack = { track: AudioTrack ->

                        runOnUiThread {
                            if (
                                generation == sessionGeneration &&
                                activeCode == code &&
                                activeAttemptId == attemptId &&
                                !isFinishing
                            ) {
                                remoteAudioTrack = track
                                track.setEnabled(audioEnabled)
                            }
                        }
                    }
                )

                webRtcSession = session

                scheduleConnectionTimeout(
                    code,
                    attemptId,
                    generation
                )

                session.startViewer()
            }
        }
    }

    private fun clearViewerMedia() {
        val oldSession = webRtcSession

        webRtcSession = null
        activeAttemptId = null

        nightVisionSink?.cancelPhotoRequest()

        remoteVideoTrack?.let { track ->
            nightVisionSink?.let {
                track.removeSink(it)
            }
        }

        remoteVideoTrack = null

        remoteAudioTrack?.setEnabled(false)
        remoteAudioTrack = null

        oldSession?.stop()

        try {
            remoteRenderer.clearImage()
        } catch (_: Exception) {
        }
    }

    private fun scheduleConnectionTimeout(
        code: String,
        attemptId: String,
        generation: Long
    ) {
        clearConnectionTimeout()

        val timeout = Runnable {
            connectionTimeoutRunnable = null

            if (
                generation == sessionGeneration &&
                activeCode == code &&
                activeAttemptId == attemptId &&
                !isFinishing
            ) {
                viewerStatus.text =
                    "KONEKSI WEBRTC BELUM BERHASIL DALAM 20 DETIK."

                viewerConnectionLabel.text = "● TIMEOUT"
                viewerConnectionLabel.setTextColor(
                    Color.rgb(255, 180, 80)
                )

                scheduleViewerReconnect(generation)
            }
        }

        connectionTimeoutRunnable = timeout
        reconnectHandler.postDelayed(timeout, 20_000L)
    }

    private fun clearConnectionTimeout() {
        connectionTimeoutRunnable?.let {
            reconnectHandler.removeCallbacks(it)
        }

        connectionTimeoutRunnable = null
    }

    private fun scheduleViewerReconnect(
        generation: Long = sessionGeneration
    ) {
        val code = activeCode ?: return

        if (
            generation != sessionGeneration ||
            reconnectRunnable != null ||
            isFinishing
        ) return

        clearConnectionTimeout()
        clearViewerMedia()
        WebRtcSignaling.clearActiveConnection(code)

        if (reconnectAttempts >= 4) {
            viewerConnectionLabel.text = "● GAGAL"
            viewerConnectionLabel.setTextColor(
                Color.rgb(255, 110, 110)
            )

            viewerStatus.text =
                "KONEKSI BELUM BERHASIL\n" +
                    "Periksa internet kedua HP. " +
                    "Jaringan tertentu memerlukan server TURN."

            return
        }

        viewerConnectionLabel.text = "● MENYAMBUNG ULANG"
        viewerConnectionLabel.setTextColor(mutedColor)

        viewerStatus.text =
            "KONEKSI TERPUTUS — MENCOBA MENYAMBUNG ULANG..."

        val retry = Runnable {
            reconnectRunnable = null

            if (
                generation == sessionGeneration &&
                activeCode == code &&
                !isFinishing
            ) {
                reconnectAttempts++
                beginViewerAttempt(code, generation)
            }
        }

        reconnectRunnable = retry
        reconnectHandler.postDelayed(retry, 2_500L)
    }

    private fun clearReconnectCallback() {
        reconnectRunnable?.let {
            reconnectHandler.removeCallbacks(it)
        }

        reconnectRunnable = null
    }

    private fun activateAudioOutput() {
        val manager =
            getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (!audioRouteCaptured) {
            previousAudioMode = manager.mode

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                previousCommunicationDevice =
                    manager.communicationDevice
            } else {
                @Suppress("DEPRECATION")
                previousSpeakerphoneState =
                    manager.isSpeakerphoneOn
            }

            audioRouteCaptured = true
        }

        manager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker =
                manager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }

            if (speaker != null) {
                manager.setCommunicationDevice(speaker)
            } else {
                @Suppress("DEPRECATION")
                manager.isSpeakerphoneOn = true
            }
        } else {
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = true
        }
    }

    private fun restoreAudioOutput() {
        if (!audioRouteCaptured) return

        val manager =
            getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.clearCommunicationDevice()

            previousCommunicationDevice?.let {
                manager.setCommunicationDevice(it)
            }
        } else {
            previousSpeakerphoneState?.let {
                @Suppress("DEPRECATION")
                manager.isSpeakerphoneOn = it
            }
        }

        previousAudioMode?.let {
            manager.mode = it
        }

        previousAudioMode = null
        previousSpeakerphoneState = null
        previousCommunicationDevice = null
        audioRouteCaptured = false
    }

    private fun stopCurrentSession(
        clearPairing: Boolean = false
    ) {
        val code = activeCode
        val savedPairing = PairingStore.get(this)

        sessionGeneration++

        clearReconnectCallback()
        clearConnectionTimeout()
        reconnectAttempts = 0

        if (recordingActive || ScreenRecordingService.isRecording) {
            stopVideoRecording()
        }

        if (code != null && viewerListener != null) {
            SessionManager.removeViewerListener(
                code,
                viewerListener!!
            )
        }

        viewerListener = null

        if (
            clearPairing &&
            savedPairing?.role == "viewer" &&
            code != null
        ) {
            WebRtcSignaling.clearActiveConnection(code)
        }

        if (
            cameraServiceStarted ||
            (clearPairing && savedPairing?.role == "camera")
        ) {
            val intent = Intent(
                this,
                MonitorService::class.java
            ).apply {
                action = MonitorService.ACTION_STOP
            }

            startService(intent)
        }

        cameraServiceStarted = false
        activeAttemptId = null

        nightVisionSink?.cancelPhotoRequest()

        remoteVideoTrack?.let { track ->
            nightVisionSink?.let {
                track.removeSink(it)
            }
        }

        remoteVideoTrack = null

        remoteAudioTrack?.setEnabled(false)
        remoteAudioTrack = null

        activeCode = null

        webRtcSession?.stop()
        webRtcSession = null

        restoreAudioOutput()

        startingCamera = false
        audioEnabled = true
        nightVisionEnabled = false

        nightVisionSink?.setNightVisionEnabled(false)

        touchLocked = false

        if (::touchLockOverlay.isInitialized) {
            touchLockOverlay.visibility = View.GONE
        }

        if (::lockButton.isInitialized) {
            lockButton.text = "KUNCI SENTUH"
        }

        cameraStatusListener?.let { listener ->
            code?.let {
                WebRtcSignaling.removeCameraStatusListener(
                    it,
                    listener
                )
            }
        }

        cameraStatusListener = null

        qrImage.visibility = View.GONE
        nightVisionButton.text = "NIGHT VISION MATI"
        audioButton.text = "SUARA AKTIF"

        if (::viewerVideoStatus.isInitialized) {
            viewerVideoStatus.text = "VIDEO: MENUNGGU TRACK"
            viewerVideoStatus.setTextColor(Color.YELLOW)
        }

        if (clearPairing) {
            PairingStore.clear(this)
        }
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        val code = activeCode

        if (code != null && viewerListener != null) {
            SessionManager.removeViewerListener(
                code,
                viewerListener!!
            )
        }

        viewerListener = null

        if (code != null && cameraStatusListener != null) {
            WebRtcSignaling.removeCameraStatusListener(
                code,
                cameraStatusListener!!
            )
        }

        cameraStatusListener = null

        sessionGeneration++

        clearReconnectCallback()
        clearConnectionTimeout()

        activeCode = null
        activeAttemptId = null

        nightVisionSink?.cancelPhotoRequest()

        remoteVideoTrack?.let { track ->
            nightVisionSink?.let {
                track.removeSink(it)
            }
        }

        remoteVideoTrack = null

        remoteAudioTrack?.setEnabled(false)
        remoteAudioTrack = null

        webRtcSession?.stop()
        webRtcSession = null

        restoreAudioOutput()

        remoteRenderer.release()

        super.onDestroy()
    }
}