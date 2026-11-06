
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

    fun getFactory(): PeerConnectionFactory {
        return factory
            ?: throw IllegalStateException(
                "WebRTC belum diinisialisasi."
            )
    }

    fun createPeerConnection(
        observer: PeerConnection.Observer
    ): PeerConnection? {
        val iceServers = listOf(
            PeerConnection.IceServer.builder(
                "stun:stun.l.google.com:19302"
            ).createIceServer()
        )

        val configuration =
            PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics =
                    PeerConnection.SdpSemantics.UNIFIED_PLAN

                bundlePolicy =
                    PeerConnection.BundlePolicy.MAXBUNDLE

                rtcpMuxPolicy =
                    PeerConnection.RtcpMuxPolicy.REQUIRE

                continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY

                iceCandidatePoolSize = 10
            }

        return getFactory().createPeerConnection(
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
