package com.monview

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object WebRtcManager {

    private const val TAG = "MonViewWebRTC"

    private const val TURN_WORKER_URL =
        "https://monview-turn.ilyasss1815.workers.dev/"

    @Volatile
    private var peerConnectionFactory: PeerConnectionFactory? = null

    @Volatile
    private var rootEglBase: EglBase? = null

    @Volatile
    private var audioDeviceModule: AudioDeviceModule? = null

    private val lock = Any()

    private val executor =
        Executors.newSingleThreadExecutor()

    private val mainHandler =
        Handler(Looper.getMainLooper())

    fun initialize(context: Context) {
        if (peerConnectionFactory != null) return

        synchronized(lock) {
            if (peerConnectionFactory != null) return

            val applicationContext =
                context.applicationContext

            val options =
                PeerConnectionFactory.InitializationOptions
                    .builder(applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()

            PeerConnectionFactory.initialize(options)

            val sharedEgl = EglBase.create()

            var configuredAudioModule: AudioDeviceModule? = null

            try {
                /*
                 * AUDIO DEVICE MODULE
                 *
                 * Menggunakan sumber audio VOICE_COMMUNICATION.
                 * Mengaktifkan hardware AEC dan NS apabila
                 * perangkat Android mendukungnya.
                 */

                val audioBuilder =
                    JavaAudioDeviceModule.builder(applicationContext)

                audioBuilder
                    .setAudioSource(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    )
                    .setUseHardwareAcousticEchoCanceler(
                        JavaAudioDeviceModule
                            .isBuiltInAcousticEchoCancelerSupported()
                    )
                    .setUseHardwareNoiseSuppressor(
                        JavaAudioDeviceModule
                            .isBuiltInNoiseSuppressorSupported()
                    )
                    .setUseStereoInput(false)
                    .setUseStereoOutput(false)

                // Catat kesalahan perekaman mikrofon.
                audioBuilder.setAudioRecordErrorCallback(
                    object : JavaAudioDeviceModule.AudioRecordErrorCallback {

                        override fun onWebRtcAudioRecordInitError(
                            errorMessage: String
                        ) {
                            Log.e(
                                TAG,
                                "AUDIO RECORD INIT ERROR: $errorMessage"
                            )
                        }

                        override fun onWebRtcAudioRecordStartError(
                            errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                            errorMessage: String
                        ) {
                            Log.e(
                                TAG,
                                "AUDIO RECORD START ERROR: " +
                                    "$errorCode - $errorMessage"
                            )
                        }

                        override fun onWebRtcAudioRecordError(
                            errorMessage: String
                        ) {
                            Log.e(
                                TAG,
                                "AUDIO RECORD ERROR: $errorMessage"
                            )
                        }
                    }
                )

                // Catat kesalahan pemutaran suara.
                audioBuilder.setAudioTrackErrorCallback(
                    object : JavaAudioDeviceModule.AudioTrackErrorCallback {

                        override fun onWebRtcAudioTrackInitError(
                            errorMessage: String
                        ) {
                            Log.e(
                                TAG,
                                "AUDIO PLAYOUT INIT ERROR: $errorMessage"
                            )
                        }

                        override fun onWebRtcAudioTrackStartError(
                            errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode,
                            errorMessage: String
                        ) {
                            Log.e(
                                TAG,
                                "AUDIO PLAYOUT START ERROR: " +
                                    "$errorCode - $errorMessage"
                            )
                        }

                        override fun onWebRtcAudioTrackError(
                            errorMessage: String
                        ) {
                            Log.e(
                                TAG,
                                "AUDIO PLAYOUT ERROR: $errorMessage"
                            )
                        }
                    }
                )

                configuredAudioModule =
                    audioBuilder.createAudioDeviceModule()

                val encoderFactory =
                    DefaultVideoEncoderFactory(
                        sharedEgl.eglBaseContext,
                        true,
                        true
                    )

                val decoderFactory =
                    DefaultVideoDecoderFactory(
                        sharedEgl.eglBaseContext
                    )

                val factory =
                    PeerConnectionFactory.builder()
                        .setAudioDeviceModule(
                            configuredAudioModule
                        )
                        .setVideoEncoderFactory(encoderFactory)
                        .setVideoDecoderFactory(decoderFactory)
                        .createPeerConnectionFactory()

                // Simpan setelah factory berhasil dibuat.
                rootEglBase = sharedEgl
                audioDeviceModule = configuredAudioModule
                peerConnectionFactory = factory

                Log.i(
                    TAG,
                    "WEBRTC SIAP — AUDIO DEVICE MODULE AKTIF"
                )

                Log.i(
                    TAG,
                    "HARDWARE AEC: " +
                        JavaAudioDeviceModule
                            .isBuiltInAcousticEchoCancelerSupported()
                )

                Log.i(
                    TAG,
                    "HARDWARE NOISE SUPPRESSOR: " +
                        JavaAudioDeviceModule
                            .isBuiltInNoiseSuppressorSupported()
                )

            } catch (e: Exception) {
                try {
                    configuredAudioModule?.release()
                } catch (_: Exception) {
                }

                try {
                    sharedEgl.release()
                } catch (_: Exception) {
                }

                Log.e(
                    TAG,
                    "WEBRTC INITIALIZATION ERROR",
                    e
                )

                throw e
            }
        }
    }

    fun getFactory(): PeerConnectionFactory? {
        return peerConnectionFactory
    }

    fun getEglBaseContext(): EglBase.Context? {
        return rootEglBase?.eglBaseContext
    }

    fun createPeerConnection(
        observer: PeerConnection.Observer,
        callback: (PeerConnection?, String?) -> Unit
    ) {
        executor.execute {

            var connection: HttpURLConnection? = null

            try {
                connection = URL(TURN_WORKER_URL)
                    .openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.doOutput = true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.outputStream.use {
                    it.write(
                        "{}".toByteArray(Charsets.UTF_8)
                    )
                }

                val status = connection.responseCode

                val response = (
                    if (status in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }
                )?.bufferedReader()?.use {
                    it.readText()
                } ?: ""

                if (status !in 200..299) {
                    throw Exception(
                        "TURN Worker HTTP $status: $response"
                    )
                }

                val json = JSONObject(response)

                val servers =
                    json.getJSONArray("iceServers")

                val iceServers =
                    mutableListOf<PeerConnection.IceServer>()

                for (i in 0 until servers.length()) {

                    val server =
                        servers.getJSONObject(i)

                    val urlsValue =
                        server.get("urls")

                    val urls = when (urlsValue) {

                        is String -> {
                            listOf(urlsValue)
                        }

                        is org.json.JSONArray -> {
                            (0 until urlsValue.length()).map {
                                urlsValue.getString(it)
                            }
                        }

                        else -> {
                            emptyList()
                        }
                    }

                    if (urls.isEmpty()) continue

                    val username =
                        server.optString("username", "")

                    val credential =
                        server.optString("credential", "")

                    val builder =
                        PeerConnection.IceServer.builder(urls)

                    if (username.isNotEmpty()) {
                        builder.setUsername(username)
                    }

                    if (credential.isNotEmpty()) {
                        builder.setPassword(credential)
                    }

                    iceServers.add(
                        builder.createIceServer()
                    )
                }

                if (iceServers.isEmpty()) {
                    throw Exception(
                        "TURN Worker tidak mengirim ICE server"
                    )
                }

                val factory =
                    peerConnectionFactory
                        ?: throw Exception(
                            "WebRTC belum diinisialisasi"
                        )

                val configuration =
                    PeerConnection.RTCConfiguration(iceServers).apply {

                        sdpSemantics =
                            PeerConnection.SdpSemantics.UNIFIED_PLAN

                        bundlePolicy =
                            PeerConnection.BundlePolicy.MAXBUNDLE

                        rtcpMuxPolicy =
                            PeerConnection.RtcpMuxPolicy.REQUIRE

                        iceTransportsType =
                            PeerConnection.IceTransportsType.ALL

                        continualGatheringPolicy =
                            PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY

                        iceCandidatePoolSize = 10
                    }

                val peerConnection =
                    factory.createPeerConnection(
                        configuration,
                        observer
                    )

                if (peerConnection == null) {
                    throw Exception(
                        "Gagal membuat PeerConnection"
                    )
                }

                mainHandler.post {
                    callback(peerConnection, null)
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "PEER CONNECTION ERROR",
                    e
                )

                mainHandler.post {
                    callback(
                        null,
                        e.message
                            ?: "Gagal mengambil konfigurasi TURN"
                    )
                }

            } finally {
                connection?.disconnect()
            }
        }
    }

    fun shutdown() {
        synchronized(lock) {

            try {
                peerConnectionFactory?.dispose()
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "FACTORY DISPOSE ERROR",
                    e
                )
            }

            peerConnectionFactory = null

            // Audio module dilepas setelah factory.
            try {
                audioDeviceModule?.release()
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "AUDIO MODULE RELEASE ERROR",
                    e
                )
            }

            audioDeviceModule = null

            try {
                rootEglBase?.release()
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "EGL RELEASE ERROR",
                    e
                )
            }

            rootEglBase = null

            Log.i(
                TAG,
                "WEBRTC BERHASIL DIMATIKAN"
            )
        }
    }
}