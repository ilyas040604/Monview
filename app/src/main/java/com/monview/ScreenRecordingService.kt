package com.monview

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileDescriptor
import java.io.IOException

class ScreenRecordingService : Service() {

    companion object {
        const val ACTION_START =
            "com.monview.action.START_SCREEN_RECORDING"

        const val ACTION_STOP =
            "com.monview.action.STOP_SCREEN_RECORDING"

        const val ACTION_STATE =
            "com.monview.action.SCREEN_RECORDING_STATE"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_IS_RECORDING = "is_recording"
        const val EXTRA_MESSAGE = "message"

        private const val CHANNEL_ID = "monview_recording"
        private const val NOTIFICATION_ID = 6201

        @Volatile
        var isRecording = false
            private set
    }

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorderSurface: Surface? = null

    private var outputUri: Uri? = null
    private var outputFile: File? = null
    private var outputDescriptor: android.os.ParcelFileDescriptor? = null

    private var stopping = false
    private var recorderStarted = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                stopRecording(stopProjection = false)
            }
        }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording(stopProjection = true)
                return START_NOT_STICKY
            }

            ACTION_START -> {
                if (isRecording) {
                    sendState(true, "REKAMAN SEDANG BERJALAN")
                    return START_NOT_STICKY
                }

                val resultCode =
                    intent.getIntExtra(EXTRA_RESULT_CODE, 0)

                val resultData: Intent? =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(
                            EXTRA_RESULT_DATA,
                            Intent::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    }

                if (resultData == null) {
                    sendState(false, "IZIN REKAM TIDAK DITERIMA")
                    stopSelf()
                    return START_NOT_STICKY
                }

                try {
                    startRecording(resultCode, resultData)
                } catch (e: Exception) {
                    stopRecording(stopProjection = true)
                    sendState(
                        false,
                        "GAGAL MEMULAI REKAMAN: ${e.message}"
                    )
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun startRecording(
        resultCode: Int,
        resultData: Intent
    ) {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val manager =
            getSystemService(MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

        projection = manager.getMediaProjection(
            resultCode,
            resultData
        ) ?: throw IOException("MediaProjection tidak tersedia")

        projection?.registerCallback(
            projectionCallback,
            mainHandler
        )

        prepareRecorder()

        val recorderInstance = recorder
            ?: throw IOException("MediaRecorder tidak tersedia")

        recorderSurface = recorderInstance.surface

        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager)
            .defaultDisplay
            .getRealMetrics(metrics)

        var width = metrics.widthPixels
        var height = metrics.heightPixels

        if (width % 2 != 0) width--
        if (height % 2 != 0) height--

        if (width < 2 || height < 2) {
            throw IOException("Ukuran layar tidak valid")
        }

        virtualDisplay = projection?.createVirtualDisplay(
            "MonViewRecording",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            recorderSurface,
            null,
            mainHandler
        ) ?: throw IOException("Virtual display gagal dibuat")

        recorderInstance.start()

        recorderStarted = true
        isRecording = true
        stopping = false

        sendState(
            true,
            "REKAMAN AKTIF — VIDEO TANPA AUDIO"
        )
    }

    private fun prepareRecorder() {
        val instance = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder = instance

        instance.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        instance.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        instance.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager)
            .defaultDisplay
            .getRealMetrics(metrics)

        var width = metrics.widthPixels
        var height = metrics.heightPixels

        if (width % 2 != 0) width--
        if (height % 2 != 0) height--

        instance.setVideoSize(width, height)
        instance.setVideoFrameRate(24)
        instance.setVideoEncodingBitRate(5_000_000)

        prepareOutput(instance)

        instance.prepare()
    }

    private fun prepareOutput(instance: MediaRecorder) {
        val fileName =
            "MonView_${System.currentTimeMillis()}.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

            val values = ContentValues().apply {
                put(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    fileName
                )
                put(
                    MediaStore.Video.Media.MIME_TYPE,
                    "video/mp4"
                )
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/MonView"
                )
                put(
                    MediaStore.MediaColumns.IS_PENDING,
                    1
                )
            }

            outputUri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IOException("Gagal membuat file video")

            outputDescriptor =
                contentResolver.openFileDescriptor(outputUri!!, "rw")
                    ?: throw IOException("Gagal membuka file video")

            instance.setOutputFile(
                outputDescriptor!!.fileDescriptor
            )

        } else {

            @Suppress("DEPRECATION")
            val moviesDirectory =
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MOVIES
                )

            val directory = File(moviesDirectory, "MonView")

            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Folder Movies/MonView gagal dibuat")
            }

            outputFile = File(directory, fileName)
            instance.setOutputFile(outputFile!!.absolutePath)
        }
    }

    private fun stopRecording(stopProjection: Boolean) {
        if (stopping) return
        stopping = true

        isRecording = false

        var saved = false

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }

        virtualDisplay = null

        if (recorderStarted) {
            try {
                recorder?.stop()
                saved = true
            } catch (_: Exception) {
                saved = false
            }
        }

        recorderStarted = false

        try {
            recorder?.reset()
        } catch (_: Exception) {
        }

        try {
            recorder?.release()
        } catch (_: Exception) {
        }

        recorder = null

        try {
            recorderSurface?.release()
        } catch (_: Exception) {
        }

        recorderSurface = null

        try {
            projection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }

        if (stopProjection) {
            try {
                projection?.stop()
            } catch (_: Exception) {
            }
        }

        projection = null

        try {
            outputDescriptor?.close()
        } catch (_: Exception) {
        }

        outputDescriptor = null

        finishOutput(saved)

        sendState(
            false,
            if (saved) {
                "VIDEO TERSIMPAN DI Movies/MonView"
            } else {
                "REKAMAN GAGAL DISIMPAN"
            }
        )

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishOutput(saved: Boolean) {
        val uri = outputUri

        if (uri != null) {
            if (saved) {
                try {
                    val values = ContentValues().apply {
                        put(
                            MediaStore.MediaColumns.IS_PENDING,
                            0
                        )
                    }

                    contentResolver.update(
                        uri,
                        values,
                        null,
                        null
                    )
                } catch (_: Exception) {
                    contentResolver.delete(uri, null, null)
                }
            } else {
                try {
                    contentResolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
            }
        }

        val file = outputFile

        if (file != null) {
            if (saved) {
                android.media.MediaScannerConnection.scanFile(
                    this,
                    arrayOf(file.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
            } else {
                file.delete()
            }
        }

        outputUri = null
        outputFile = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MonView Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status perekaman video MonView"
            }

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, ScreenRecordingService::class.java)
            .setAction(ACTION_STOP)

        val stopPendingIntent = PendingIntent.getService(
            this,
            6202,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("MonView sedang merekam")
            .setContentText("Video sedang disimpan di Movies/MonView")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_media_pause,
                "HENTIKAN",
                stopPendingIntent
            )
            .build()
    }

    private fun sendState(
        active: Boolean,
        message: String
    ) {
        val intent = Intent(ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_RECORDING, active)
            putExtra(EXTRA_MESSAGE, message)
        }

        sendBroadcast(intent)
    }

    override fun onDestroy() {
        if (!stopping) {
            stopRecording(stopProjection = true)
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}