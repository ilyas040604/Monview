
package com.monview

import android.content.Context
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory

object WebRtcManager {

    @Volatile
    private var factory: PeerConnectionFactory? = null

    @Synchronized
    fun initialize(context: Context) {
        if (factory != null) return

        val appContext = context.applicationContext

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(appContext)
                .createInitializationOptions()
        )

        factory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()
    }

    fun createPeerConnection(
        observer: PeerConnection.Observer
    ): PeerConnection? {
        val currentFactory = factory
            ?: throw IllegalStateException(
                "WebRTC belum diinisialisasi."
            )

        val iceServers = listOf(
            PeerConnection.IceServer.builder(
                "stun:stun.l.google.com:19302"
            ).createIceServer()
        )

        val configuration =
            PeerConnection.RTCConfiguration(iceServers)

        return currentFactory.createPeerConnection(
            configuration,
            observer
        )
    }

    @Synchronized
    fun shutdown() {
        factory?.dispose()
        factory = null
    }
}
