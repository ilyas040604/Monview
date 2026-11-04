
package com.monview

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var qrImage: ImageView

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

        status = TextView(this).apply {
            text = "MONVIEW\n\nPilih peran perangkat."
            textSize = 17f
            setPadding(24, 32, 24, 24)
            gravity = Gravity.CENTER
        }

        qrImage = ImageView(this).apply {
            adjustViewBounds = true
            visibility = ImageView.GONE
        }

        val cameraButton = Button(this).apply {
            text = "Gunakan sebagai Mode Kamera"
            setOnClickListener { requestCameraPermissions() }
        }

        val viewerButton = Button(this).apply {
            text = "Gunakan sebagai Mode Pemantau"
            setOnClickListener {
                qrImage.visibility = ImageView.GONE
                status.text =
                    "Mode Pemantau dipilih. Pemindaian QR belum tersedia."
            }
        }

        val stopButton = Button(this).apply {
            text = "Hentikan layanan"
            setOnClickListener {
                stopService(
                    Intent(this@MainActivity, MonitorService::class.java)
                )
                qrImage.visibility = ImageView.GONE
                status.text = "Layanan MonView dihentikan."
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 24, 24, 24)
            addView(status)
            addView(qrImage)
            addView(cameraButton)
            addView(viewerButton)
            addView(stopButton)
        }

        setContentView(layout)
    }

    private fun requestCameraPermissions() {
        val needed = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= 33) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

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
        qrImage.visibility = ImageView.GONE
        status.text = "Menghubungkan ke Firebase..."

        FirebaseManager.login { success, message ->
            runOnUiThread {
                if (!success) {
                    status.text = "Gagal masuk Firebase: $message"
                    return@runOnUiThread
                }

                SessionManager.createSession { created, result ->
                    runOnUiThread {
                        if (created) {
                            try {
                                val bitmap = QrCodeGenerator.generate(result)
                                qrImage.setImageBitmap(bitmap)
                                qrImage.visibility = ImageView.VISIBLE

                                status.text =
                                    "Sesi kamera berhasil dibuat.\n" +
                                    "Kode sesi:\n$result\n\n" +
                                    "Berlaku 60 detik."
                            } catch (e: Exception) {
                                status.text =
                                    "Sesi dibuat, tetapi QR gagal dibuat: ${e.message}"
                            }
                        } else {
                            status.text = "Gagal membuat sesi: $result"
                        }
                    }
                }
            }
        }
    }
}
