
package com.monview

import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.ValueEventListener
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SdpObserver
import org.webrtc.VideoTrack

class WebRtcSession(
    private val code: String,
    private val isCamera: Boolean,
    private val onRemoteTrack: (VideoTrack) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onRemoteAudioTrack: (AudioTrack) -> Unit = {}
) {
    private var peerConnection: PeerConnection? = null
    private var answerListener: ValueEventListener? = null
    private var offerListener: ValueEventListener? = null
    private var candidateListener: ChildEventListener? = null

    private val pendingCandidates = mutableListOf<IceCandidate>()
    private val deliveredTrackIds = mutableSetOf<String>()

    private var remoteDescriptionReady = false

    private fun deliverTrack(track: MediaStreamTrack?) {
        when (track) {
            is VideoTrack -> {
                if (deliveredTrackIds.add(track.id())) {
                    track.setEnabled(true)
                    onRemoteTrack(track)
                    onStatus("VIDEO TRACK DITERIMA")
                }
            }

            is AudioTrack -> {
                if (deliveredTrackIds.add(track.id())) {
                    track.setEnabled(true)
                    onRemoteAudioTrack(track)
                    onStatus("AUDIO TRACK DITERIMA")
                }
            }
        }
    }

    private val observer = object : PeerConnection.Observer {

        override fun onSignalingChange(
            state: PeerConnection.SignalingState
        ) {
            onStatus("Signaling: $state")
        }

        override fun onIceConnectionChange(
            state: PeerConnection.IceConnectionState
        ) {
            onStatus("ICE: $state")
        }

        override fun onConnectionChange(
            state: PeerConnection.PeerConnectionState
        ) {
            onStatus("WEBRTC: $state")
        }

        override fun onIceConnectionReceivingChange(
            receiving: Boolean
        ) {
            onStatus(
                if (receiving) "MENERIMA DATA ICE"
                else "BELUM MENERIMA DATA ICE"
            )
        }

        override fun onIceGatheringChange(
            state: PeerConnection.IceGatheringState
        ) {
            onStatus("Pengumpulan ICE: $state")
        }

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
            stream.videoTracks.firstOrNull()?.let {
                deliverTrack(it)
            }

            stream.audioTracks.firstOrNull()?.let {
                deliverTrack(it)
            }
        }

        override fun onRemoveStream(stream: MediaStream) {}

        override fun onDataChannel(
            dataChannel: org.webrtc.DataChannel
        ) {}

        override fun onRenegotiationNeeded() {
            onStatus("Negosiasi ulang diperlukan")
        }

        override fun onAddTrack(
            receiver: RtpReceiver,
            mediaStreams: Array<MediaStream>
        ) {
            deliverTrack(receiver.track())
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            deliverTrack(transceiver.receiver.track())
        }
    }

    private fun addRemoteCandidate(candidate: IceCandidate) {
        val connection = peerConnection ?: return

        if (remoteDescriptionReady) {
            connection.addIceCandidate(candidate)
        } else {
            pendingCandidates.add(candidate)
        }
    }

    private fun flushPendingCandidates() {
        val connection = peerConnection ?: return

        remoteDescriptionReady = true

        pendingCandidates.forEach {
            connection.addIceCandidate(it)
        }

        val count = pendingCandidates.size
        pendingCandidates.clear()

        onStatus("Deskripsi diterima. ICE kandidat: $count")
    }

    fun startCamera(stream: CameraStream) {
        if (!isCamera || peerConnection != null) return

        val connection =
            WebRtcManager.createPeerConnection(observer)

        if (connection == null) {
            onStatus("GAGAL MEMBUAT PEER CONNECTION")
            return
        }

        peerConnection = connection

        val video = stream.videoTrack
        val audio = stream.audioTrack

        if (video == null || audio == null) {
            onStatus("TRACK KAMERA ATAU MIKROFON KOSONG")
            return
        }

        video.setEnabled(true)
        audio.setEnabled(true)

        connection.addTrack(video, listOf("monview_stream"))
        connection.addTrack(audio, listOf("monview_stream"))

        onStatus("VIDEO DAN AUDIO DITAMBAHKAN")

        candidateListener =
            WebRtcSignaling.listenCandidates(code, true) {
                addRemoteCandidate(it)
            }

        answerListener =
            WebRtcSignaling.listenAnswer(code) { answer ->
                if (
                    answer != null &&
                    connection.signalingState() ==
                    PeerConnection.SignalingState.HAVE_LOCAL_OFFER
                ) {
                    connection.setRemoteDescription(
                        object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                flushPendingCandidates()
                                onStatus("ANSWER DITERIMA KAMERA")
                            }

                            override fun onSetFailure(error: String) {
                                onStatus("ANSWER GAGAL: $error")
                            }
                        },
                        answer
                    )
                }
            }

        connection.createOffer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(
                    description: SessionDescription
                ) {
                    connection.setLocalDescription(
                        object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                WebRtcSignaling.sendOffer(
                                    code,
                                    description
                                ) { success, message ->
                                    onStatus(
                                        if (success) "OFFER TERKIRIM"
                                        else "OFFER GAGAL: $message"
                                    )
                                }
                            }

                            override fun onSetFailure(error: String) {
                                onStatus("LOCAL DESCRIPTION GAGAL: $error")
                            }
                        },
                        description
                    )
                }

                override fun onCreateFailure(error: String) {
                    onStatus("GAGAL MEMBUAT OFFER: $error")
                }
            },
            MediaConstraints()
        )
    }

    fun startViewer() {
        if (isCamera || peerConnection != null) return

        val connection =
            WebRtcManager.createPeerConnection(observer)

        if (connection == null) {
            onStatus("GAGAL MEMBUAT PEER CONNECTION")
            return
        }

        peerConnection = connection
        onStatus("MENUNGGU OFFER KAMERA")

        candidateListener =
            WebRtcSignaling.listenCandidates(code, false) {
                addRemoteCandidate(it)
            }

        offerListener =
            WebRtcSignaling.listenOffer(code) { offer ->
                if (
                    offer != null &&
                    connection.signalingState() ==
                    PeerConnection.SignalingState.STABLE
                ) {
                    onStatus("OFFER DITERIMA PEMANTAU")

                    connection.setRemoteDescription(
                        object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                flushPendingCandidates()

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
                                                        ) { success, message ->
                                                            onStatus(
                                                                if (success) "ANSWER TERKIRIM"
                                                                else "ANSWER GAGAL: $message"
                                                            )
                                                        }
                                                    }

                                                    override fun onSetFailure(
                                                        error: String
                                                    ) {
                                                        onStatus(
                                                            "LOCAL ANSWER GAGAL: $error"
                                                        )
                                                    }
                                                },
                                                answer
                                            )
                                        }

                                        override fun onCreateFailure(
                                            error: String
                                        ) {
                                            onStatus(
                                                "GAGAL MEMBUAT ANSWER: $error"
                                            )
                                        }
                                    },
                                    MediaConstraints()
                                )
                            }

                            override fun onSetFailure(error: String) {
                                onStatus("OFFER GAGAL DITERIMA: $error")
                            }
                        },
                        offer
                    )
                }
            }
    }

    fun stop() {
        answerListener?.let {
            WebRtcSignaling.removeAnswerListener(code, it)
        }

        offerListener?.let {
            WebRtcSignaling.removeOfferListener(code, it)
        }

        candidateListener?.let {
            WebRtcSignaling.removeCandidateListener(
                code,
                isCamera,
                it
            )
        }

        answerListener = null
        offerListener = null
        candidateListener = null

        pendingCandidates.clear()
        deliveredTrackIds.clear()
        remoteDescriptionReady = false

        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null

        onStatus("KONEKSI DIHENTIKAN")
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}
