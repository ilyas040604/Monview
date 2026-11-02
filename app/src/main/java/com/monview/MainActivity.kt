package com.monview

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            if (results.isNotEmpty() && results.values.all { it }) {
                startMonitorService()
            } else {
                status.text = "Izin belum lengkap. Mode Kamera belum dapat diaktifkan."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "MONVIEW\n\nPilih peran perangkat ini.\n\nMode Kamera: perangkat yang menyediakan kamera.\nMode Pemantau: perangkat untuk melihat kamera setelah fitur pairing tersedia."
            textSize = 17f
            setPadding(24, 32, 24, 24)
        }

        val cameraButton = Button(this).apply {
            text = "Gunakan sebagai Mode Kamera"
            setOnClickListener { requestCameraPermissions() }
        }

        val viewerButton = Button(this).apply {
            text = "Gunakan sebagai Mode Pemantau"
            setOnClickListener {
                status.text = "Mode Pemantau dipilih. Fitur scan QR dan koneksi sedang dalam pengembangan."
            }
        }

        val stopButton = Button(this).apply {
            text = "Hentikan layanan"
            setOnClickListener {
                stopService(Intent(this@MainActivity, MonitorService::class.java))
                status.text = "Layanan MonView dihentikan."
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(status)
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
            startMonitorService()
        }
    }

    private fun startMonitorService() {
        val intent = Intent(this, MonitorService::class.java)
        ContextCompat.startForegroundService(this, intent)
        status.text = "Mode Kamera dipilih. Layanan dasar aktif; streaming kamera belum tersedia."
    }
}