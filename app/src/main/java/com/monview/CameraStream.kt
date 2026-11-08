
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
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

class CameraStream(
    private val context: Context,
    private val onStatus: (String) -> Unit = {}
) {

    private var capturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var diagnosticSink: VideoSink? = null

    private val firstTrackFrameReported = AtomicBoolean(false)

    var videoTrack: VideoTrack? = null
        private set

    var audioTrack: AudioTrack? = null
        private set

    var lastError: String? = null
        private set

    private val cameraEvents =
        object : CameraVideoCapturer.CameraEventsHandler {

            override fun onCameraError(errorDescription: String) {
                lastError = errorDescription
                onStatus("ERROR KAMERA: $errorDescription")
            }

            override fun onCameraDisconnected() {
                onStatus("KAMERA TERPUTUS")
            }

            override fun onCameraFreezed(errorDescription: String) {
                lastError = errorDescription
                onStatus("KAMERA MACET: $errorDescription")
            }

            override fun onCameraOpening(cameraName: String) {
                onStatus("MEMBUKA KAMERA: $cameraName")
            }

            override fun onFirstFrameAvailable() {
                onStatus("CAPTURE KAMERA: FRAME PERTAMA DITERIMA")
            }

            override fun onCameraClosed() {
                onStatus("KAMERA DITUTUP")
            }
        }

    fun start(): Boolean {
        if (videoTrack != null && audioTrack != null) return true

        lastError = null
        firstTrackFrameReported.set(false)

        val cameraPermission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

        val audioPermission =
            ContextCompat.checkSelfPermission(
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
            val cameraNames = enumerator.deviceNames

            if (cameraNames.isEmpty()) {
                return fail("HP TIDAK MENEMUKAN KAMERA")
            }

            val cameraName = cameraNames.firstOrNull {
                enumerator.isFrontFacing(it)
            } ?: cameraNames.first()

            onStatus("KAMERA DIPILIH: $cameraName")

            val camera = enumerator.createCapturer(
                cameraName,
                cameraEvents
            ) ?: return fail("GAGAL MEMBUAT CAMERA CAPTURER")

            capturer = camera

            val helper = SurfaceTextureHelper.create(
                "MonViewCameraThread",
                eglContext
            ) ?: return fail("GAGAL MEMBUAT SURFACE TEXTURE HELPER")

            textureHelper = helper

            val video = factory.createVideoSource(false)
            videoSource = video

            camera.initialize(
                helper,
                context,
                video.capturerObserver
            )

            val videoMediaTrack = factory.createVideoTrack(
                "monview_video",
                video
            )

            videoMediaTrack.setEnabled(true)
            videoTrack = videoMediaTrack

            // Memastikan frame benar-benar masuk ke VideoTrack.
            val sink = VideoSink { frame: VideoFrame ->
                if (firstTrackFrameReported.compareAndSet(false, true)) {
                    onStatus(
                        "VIDEO TRACK MENERIMA FRAME: " +
                            "${frame.buffer.width}x${frame.buffer.height}"
                    )
                }
            }

            diagnosticSink = sink
            videoMediaTrack.addSink(sink)

            val audio = factory.createAudioSource(
                MediaConstraints()
            )
            audioSource = audio

            val audioMediaTrack = factory.createAudioTrack(
                "monview_audio",
                audio
            )

            audioMediaTrack.setEnabled(true)
            audioTrack = audioMediaTrack

            onStatus("TRACK VIDEO DAN AUDIO SIAP — MEMULAI CAPTURE")

            camera.startCapture(1280, 720, 24)

            onStatus("CAPTURE DIMULAI — MENUNGGU FRAME VIDEO TRACK")
            true

        } catch (e: Exception) {
            fail(
                "GAGAL MEMULAI KAMERA: " +
                    (e.message ?: e.javaClass.simpleName)
            )
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
                    lastError = error
                    onResult(false, error)
                }
            }
        )
    }

    fun stop() {
        try {
            diagnosticSink?.let { sink ->
                videoTrack?.removeSink(sink)
            }
        } catch (_: Exception) {
        }
        diagnosticSink = null

        try {
            capturer?.stopCapture()
        } catch (_: Exception) {
        }

        try {
            capturer?.dispose()
        } catch (_: Exception) {
        }
        capturer = null

        try {
            videoTrack?.dispose()
        } catch (_: Exception) {
        }
        videoTrack = null

        try {
            audioTrack?.dispose()
        } catch (_: Exception) {
        }
        audioTrack = null

        try {
            videoSource?.dispose()
        } catch (_: Exception) {
        }
        videoSource = null

        try {
            audioSource?.dispose()
        } catch (_: Exception) {
        }
        audioSource = null

        try {
            textureHelper?.dispose()
        } catch (_: Exception) {
        }
        textureHelper = null

        firstTrackFrameReported.set(false)
    }
}
