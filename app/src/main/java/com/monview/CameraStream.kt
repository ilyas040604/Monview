
package com.monview

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class CameraStream(private val context: Context) {

    private var capturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var eglBase: EglBase? = null

    var videoTrack: VideoTrack? = null
        private set

    var audioTrack: AudioTrack? = null
        private set

    fun start(): Boolean {
        if (videoTrack != null && audioTrack != null) return true

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

        if (!cameraPermission || !audioPermission) return false

        return try {
            WebRtcManager.initialize(context)
            val factory = WebRtcManager.getFactory()

            val enumerator = Camera2Enumerator(context)

            val cameraName =
                enumerator.deviceNames.firstOrNull {
                    enumerator.isFrontFacing(it)
                } ?: enumerator.deviceNames.firstOrNull()
                    ?: return false

            val camera =
                enumerator.createCapturer(cameraName, null)
                    ?: return false

            val egl = EglBase.create()

            val helper = SurfaceTextureHelper.create(
                "MonViewCameraThread",
                egl.eglBaseContext
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
            eglBase = egl
            textureHelper = helper
            videoSource = video
            audioSource = audio
            videoTrack = videoMediaTrack
            audioTrack = audioMediaTrack

            true
        } catch (e: Exception) {
            stop()
            false
        }
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

        eglBase?.release()
        eglBase = null
    }
}
