
package com.monview

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object WebRtcManager {

    private const val TURN_WORKER_URL =
        "https://monview-turn.ilyasss1815.workers.dev/"

    @Volatile
    private var peerConnectionFactory: PeerConnectionFactory? = null

    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun initialize(context: Context) {
        if (peerConnectionFactory != null) return

        synchronized(lock) {
            if (peerConnectionFactory != null) return

            val options =
                PeerConnectionFactory.InitializationOptions
                    .builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()

            PeerConnectionFactory.initialize(options)

            val encoderFactory =
                org.webrtc.DefaultVideoEncoderFactory(
                    null,
                    true,
                    true
                )

            val decoderFactory =
                org.webrtc.DefaultVideoDecoderFactory(null)

            peerConnectionFactory =
                PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(encoderFactory)
                    .setVideoDecoderFactory(decoderFactory)
                    .createPeerConnectionFactory()
        }
    }

    fun getFactory(): PeerConnectionFactory? {
        return peerConnectionFactory
    }

    fun createPeerConnection(
        observer: PeerConnection.Observer,
        callback: (PeerConnection?, String?) -> Unit
    ) {
        executor.execute {
            try {
                val connection = URL(TURN_WORKER_URL)
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
                    it.write("{}".toByteArray(Charsets.UTF_8))
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

                connection.disconnect()

                if (status !in 200..299) {
                    throw Exception(
                        "TURN Worker HTTP $status: $response"
                    )
                }

                val json = JSONObject(response)
                val servers = json.getJSONArray("iceServers")

                val iceServers =
                    mutableListOf<PeerConnection.IceServer>()

                for (i in 0 until servers.length()) {
                    val server = servers.getJSONObject(i)
                    val urlsValue = server.get("urls")

                    val urls = when (urlsValue) {
                        is String -> listOf(urlsValue)
                        is org.json.JSONArray -> {
                            (0 until urlsValue.length()).map {
                                urlsValue.getString(it)
                            }
                        }
                        else -> emptyList()
                    }

                    if (urls.isEmpty()) continue

                    val username = server.optString("username", "")
                    val credential = server.optString("credential", "")

                    val builder =
                        PeerConnection.IceServer.builder(urls)

                    if (username.isNotEmpty()) {
                        builder.setUsername(username)
                    }

                    if (credential.isNotEmpty()) {
                        builder.setPassword(credential)
                    }

                    iceServers.add(builder.createIceServer())
                }

                if (iceServers.isEmpty()) {
                    throw Exception("TURN Worker tidak mengirim ICE server")
                }

                val factory =
                    peerConnectionFactory
                        ?: throw Exception("WebRTC belum diinisialisasi")

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
                    factory.createPeerConnection(configuration, observer)

                if (peerConnection == null) {
                    throw Exception("Gagal membuat PeerConnection")
                }

                mainHandler.post {
                    callback(peerConnection, null)
                }

            } catch (e: Exception) {
                mainHandler.post {
                    callback(
                        null,
                        e.message ?: "Gagal mengambil konfigurasi TURN"
                    )
                }
            }
        }
    }

    fun shutdown() {
        synchronized(lock) {
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
        }
    }
}
