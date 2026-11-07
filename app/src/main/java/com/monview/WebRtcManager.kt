package com.monview

import android.content.Context
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory

object WebRtcManager {

    @Volatile
    private var peerConnectionFactory: PeerConnectionFactory? = null

    private val lock = Any()

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
        observer: PeerConnection.Observer
    ): PeerConnection? {

        val factory = peerConnectionFactory ?: return null

        val iceServers = listOf(
            PeerConnection.IceServer
                .builder("stun:stun.l.google.com:19302")
                .createIceServer(),

            PeerConnection.IceServer
                .builder("stun:stun1.l.google.com:19302")
                .createIceServer(),

            PeerConnection.IceServer
                .builder("stun:stun.cloudflare.com:3478")
                .createIceServer()
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

        return factory.createPeerConnection(
            configuration,
            observer
        )
    }

    fun shutdown() {
        synchronized(lock) {
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null
        }
    }
}