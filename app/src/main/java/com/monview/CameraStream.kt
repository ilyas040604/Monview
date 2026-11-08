
package com.monview

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.MediaConstraints
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class CameraStream(
    private val context: Context,
    private val onStatus: (String) -> Unit = {}
) {

    private var capturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var textureHelper: SurfaceTextureHelper? = null

    var videoTrack: VideoTrack? = null
        private set

    var audioTrack: AudioTrack? = null
        private set

    var lastError: String? = null
        private set

    private val cameraEvents = object : CameraVideoCapturer.CameraEventsHandler {

        override fun onCameraError(errorDescription: String) {
            lastError = errorDescription
            onStatus("ERROR KAMERA: $errorDescription")
        }

        override fun onCameraDisconnected() {
            onStatus("KAMERA TERPUTUS")
        }

        override fun onCameraFreezed(errorDescription: String) {
            onStatus("KAMERA MACET: $errorDescription")
        }

        override fun onCameraOpening(cameraName: String) {
            onStatus("MEMBUKA KAMERA: $cameraName")
        }

        override fun onFirstFrameAvailable() {
            onStatus("FRAME VIDEO PERTAMA BERHASIL DITERIMA")
        }

        override fun onCameraClosed() {
            onStatus("KAMERA DITUTUP")
        }
    }

    fun start(): Boolean {
        if (videoTrack != null && audioTrack != null) return true

        lastError = null

        val cameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val audioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!cameraPermission || !audioPermission) {
            return fail("IZIN KAMERA ATAU MIKROFON BELUM DIBERIKAN")
        }

        return try {
            WebRtcManager.initialize(context)

            val factory = WebRtcManager.getFactory()
                ?: return fail("WEBRTC FACTORY TIDAK TERSEDIA")

            val eglContext = WebRtcManager.getEglBaseContext()
                ?: return fail("EGL CONTEXT TIDAK TERSEDIA")

            val enumerator = Camera2Enumerator(context)

            val cameraName = enumerator.deviceNames.firstOrNull {
                enumerator.isFrontFacing(it)
            } ?: enumerator.deviceNames.firstOrNull()
                ?: return fail("HP TIDAK MENEMUKAN KAMERA")

            val camera = enumerator.createCapturer(
                cameraName,
                cameraEvents
            ) ?: return fail("GAGAL MEMBUAT CAMERA CAPTURER")

            val helper = SurfaceTextureHelper.create(
                "MonViewCameraThread",
                eglContext
            ) ?: return fail("GAGAL MEMBUAT SURFACE TEXTURE HELPER")

            val video = factory.createVideoSource(false)

            camera.initialize(
                helper,
                context,
                video.capturerObserver
            )

            camera.startCapture(1280, 720, 24)

            val videoMediaTrack = factory.createVideoTrack(
                "monview_video",
                video
            )

            val audio = factory.createAudioSource(
                MediaConstraints()
            )

            val audioMediaTrack = factory.createAudioTrack(
                "monview_audio",
                audio
            )

            capturer = camera
            textureHelper = helper
            videoSource = video
            audioSource = audio
            videoTrack = videoMediaTrack
            audioTrack = audioMediaTrack

            onStatus("CAPTURE KAMERA DIMULAI")
            true

        } catch (e: Exception) {
            fail("GAGAL MEMULAI KAMERA: ${e.message}")
        }
    }

    private fun fail(message: String): Boolean {
        lastError = message
        onStatus(message)
        stop()
        return false
    }

    fun switchCamera(onResult: (Boolean, String) -> Unit) {
        val activeCapturer = capturer

        if (activeCapturer == null) {
            onResult(false, "Kamera belum aktif")
            return
        }

        activeCapturer.switchCamera(
            object : CameraVideoCapturer.CameraSwitchHandler {

                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    onResult(
                        true,
                        if (isFrontCamera) {
                            "Kamera depan aktif"
                        } else {
                            "Kamera belakang aktif"
                        }
                    )
                }

                override fun onCameraSwitchError(error: String) {
                    onResult(false, error)
                }
            }
        )
    }

    fun stop() {
        try {
            capturer?.stopCapture()
        } catch (_: Exception) {
        }

        capturer?.dispose()
        capturer = null

        videoTrack?.dispose()
        videoTrack = null

        audioTrack?.dispose()
        audioTrack = null

        videoSource?.dispose()
        videoSource = null

        audioSource?.dispose()
        audioSource = null

        textureHelper?.dispose()
        textureHelper = null
    }
}
