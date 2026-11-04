
package com.monview

import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SdpObserver

class WebRtcSession(
    private val code: String,
    private val isCamera: Boolean,
    private val onRemoteTrack: (org.webrtc.VideoTrack) -> Unit,
    private val onStatus: (String) -> Unit
) {

    private var peerConnection: PeerConnection? = null
    private var answerListener:
        com.google.firebase.database.ValueEventListener? = null
    private var offerListener:
        com.google.firebase.database.ValueEventListener? = null
    private var candidateListener:
        com.google.firebase.database.ChildEventListener? = null

    private val observer = object : PeerConnection.Observer {

        override fun onSignalingChange(
            state: PeerConnection.SignalingState
        ) {}

        override fun onIceConnectionChange(
            state: PeerConnection.IceConnectionState
        ) {
            onStatus("Koneksi: $state")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(
            state: PeerConnection.IceGatheringState
        ) {}

        override fun onIceCandidate(candidate: IceCandidate) {
            WebRtcSignaling.sendCandidate(
                code,
                candidate,
                isCamera
            )
        }

        override fun onIceCandidatesRemoved(
            candidates: Array<IceCandidate>
        ) {}

        override fun onAddStream(stream: MediaStream) {
            stream.videoTracks.firstOrNull()?.let(onRemoteTrack)
        }

        override fun onRemoveStream(stream: MediaStream) {}

        override fun onDataChannel(
            dataChannel: org.webrtc.DataChannel
        ) {}

        override fun onRenegotiationNeeded() {}

        override fun onAddTrack(
            receiver: RtpReceiver,
            mediaStreams: Array<MediaStream>
        ) {
            receiver.track()?.let { track ->
                if (track is org.webrtc.VideoTrack) {
                    onRemoteTrack(track)
                }
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is org.webrtc.VideoTrack) {
                onRemoteTrack(track)
            }
        }
    }

    fun startCamera(stream: CameraStream) {
        if (!isCamera) return

        val connection = WebRtcManager.createPeerConnection(observer)
            ?: run {
                onStatus("Gagal membuat koneksi")
                return
            }

        peerConnection = connection

        val video = stream.videoTrack
        val audio = stream.audioTrack

        if (video == null || audio == null) {
            onStatus("Kamera atau mikrofon belum siap")
            return
        }

        connection.addTrack(video, listOf("monview_stream"))
        connection.addTrack(audio, listOf("monview_stream"))

        candidateListener = WebRtcSignaling.listenCandidates(
            code,
            true
        ) { candidate ->
            connection.addIceCandidate(candidate)
        }

        answerListener = WebRtcSignaling.listenAnswer(code) { answer ->
            if (answer != null &&
                connection.signalingState() ==
                PeerConnection.SignalingState.HAVE_LOCAL_OFFER
            ) {
                connection.setRemoteDescription(
                    SimpleSdpObserver(),
                    answer
                )
            }
        }

        connection.createOffer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(description: SessionDescription) {
                    connection.setLocalDescription(
                        object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                WebRtcSignaling.sendOffer(code, description) {
                                        success, message ->
                                    onStatus(message)
                                }
                            }
                        },
                        description
                    )
                }

                override fun onCreateFailure(error: String) {
                    onStatus("Gagal membuat offer: $error")
                }
            },
            org.webrtc.MediaConstraints()
        )
    }

    fun startViewer() {
        if (isCamera) return

        val connection = WebRtcManager.createPeerConnection(observer)
            ?: run {
                onStatus("Gagal membuat koneksi")
                return
            }

        peerConnection = connection

        candidateListener = WebRtcSignaling.listenCandidates(
            code,
            false
        ) { candidate ->
            connection.addIceCandidate(candidate)
        }

        offerListener = WebRtcSignaling.listenOffer(code) { offer ->
            if (offer != null &&
                connection.signalingState() ==
                PeerConnection.SignalingState.STABLE
            ) {
                connection.setRemoteDescription(
                    object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            connection.createAnswer(
                                object : SimpleSdpObserver() {
                                    override fun onCreateSuccess(
                                        answer: SessionDescription
                                    ) {
                                        connection.setLocalDescription(
                                            object : SimpleSdpObserver() {
                                                override fun onSetSuccess() {
                                                    WebRtcSignaling.sendAnswer(
                                                        code,
                                                        answer
                                                    ) { _, message ->
                                                        onStatus(message)
                                                    }
                                                }
                                            },
                                            answer
                                        )
                                    }

                                    override fun onCreateFailure(error: String) {
                                        onStatus("Gagal membuat answer: $error")
                                    }
                                },
                                org.webrtc.MediaConstraints()
                            )
                        }
                    },
                    offer
                )
            }
        }
    }

    fun stop() {
        answerListener?.let {
            WebRtcSignaling.run {
                // Listener dilepas pada langkah pembersihan berikutnya.
            }
        }

        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null

        onStatus("Koneksi dihentikan")
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}
