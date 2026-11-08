
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

class CameraStream(private val context: Context) {

    private var capturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var textureHelper: SurfaceTextureHelper? = null

    var videoTrack: VideoTrack? = null
        private set

    var audioTrack: AudioTrack? = null
        private set

    fun start(): Boolean {
        if (videoTrack != null && audioTrack != null) return true

        val cameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val audioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!cameraPermission || !audioPermission) return false

        return try {
            WebRtcManager.initialize(context)

            val factory = WebRtcManager.getFactory()
                ?: return false

            val eglContext = WebRtcManager.getEglBaseContext()
                ?: return false

            val enumerator = Camera2Enumerator(context)

            val cameraName = enumerator.deviceNames.firstOrNull {
                enumerator.isFrontFacing(it)
            } ?: enumerator.deviceNames.firstOrNull()
                ?: return false

            val camera = enumerator.createCapturer(cameraName, null)
                ?: return false

            val helper = SurfaceTextureHelper.create(
                "MonViewCameraThread",
                eglContext
            ) ?: throw IllegalStateException(
                "Gagal menyiapkan kamera"
            )

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

            true

        } catch (_: Exception) {
            stop()
            false
        }
    }

    fun switchCamera(onResult: (Boolean, String) -> Unit) {
        val activeCapturer = capturer

        if (activeCapturer == null) {
            onResult(false, "Kamera belum aktif")
            return
        }

        activeCapturer.switchCamera(
            object : CameraVideoCapturer.CameraSwitchHandler {

                override fun onCameraSwitchDone(
                    isFrontCamera: Boolean
                ) {
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
