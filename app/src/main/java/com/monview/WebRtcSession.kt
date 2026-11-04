
package com.monview

import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.ValueEventListener
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
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
    private val onStatus: (String) -> Unit
) {
    private var peerConnection: PeerConnection? = null
    private var answerListener: ValueEventListener? = null
    private var offerListener: ValueEventListener? = null
    private var candidateListener: ChildEventListener? = null

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            onStatus("Koneksi: $state")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}

        override fun onIceCandidate(candidate: IceCandidate) {
            WebRtcSignaling.sendCandidate(code, candidate, isCamera)
        }

        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}

        override fun onAddStream(stream: MediaStream) {
            stream.videoTracks.firstOrNull()?.let { onRemoteTrack(it) }
        }

        override fun onRemoveStream(stream: MediaStream) {}

        override fun onDataChannel(dataChannel: org.webrtc.DataChannel) {}

        override fun onRenegotiationNeeded() {}

        override fun onAddTrack(
            receiver: RtpReceiver,
            mediaStreams: Array<MediaStream>
        ) {
            val track = receiver.track()
            if (track is VideoTrack) onRemoteTrack(track)
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) onRemoteTrack(track)
        }
    }

    fun startCamera(stream: CameraStream) {
        if (!isCamera) return

        val connection = WebRtcManager.createPeerConnection(observer)
        if (connection == null) {
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

        candidateListener = WebRtcSignaling.listenCandidates(code, true) {
            connection.addIceCandidate(it)
        }

        answerListener = WebRtcSignaling.listenAnswer(code) { answer ->
            if (answer != null &&
                connection.signalingState() ==
                PeerConnection.SignalingState.HAVE_LOCAL_OFFER
            ) {
                connection.setRemoteDescription(SimpleSdpObserver(), answer)
            }
        }

        connection.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                connection.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        WebRtcSignaling.sendOffer(code, description) { success, message ->
                            onStatus(message)
                        }
                    }

                    override fun onSetFailure(error: String) {
                        onStatus("Gagal menyimpan offer: $error")
                    }
                }, description)
            }

            override fun onCreateFailure(error: String) {
                onStatus("Gagal membuat offer: $error")
            }
        }, org.webrtc.MediaConstraints())
    }

    fun startViewer() {
        if (isCamera) return

        val connection = WebRtcManager.createPeerConnection(observer)
        if (connection == null) {
            onStatus("Gagal membuat koneksi")
            return
        }
        peerConnection = connection

        candidateListener = WebRtcSignaling.listenCandidates(code, false) {
            connection.addIceCandidate(it)
        }

        offerListener = WebRtcSignaling.listenOffer(code) { offer ->
            if (offer != null &&
                connection.signalingState() == PeerConnection.SignalingState.STABLE
            ) {
                connection.setRemoteDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        connection.createAnswer(object : SimpleSdpObserver() {
                            override fun onCreateSuccess(answer: SessionDescription) {
                                connection.setLocalDescription(object : SimpleSdpObserver() {
                                    override fun onSetSuccess() {
                                        WebRtcSignaling.sendAnswer(code, answer) { _, message ->
                                            onStatus(message)
                                        }
                                    }

                                    override fun onSetFailure(error: String) {
                                        onStatus("Gagal menyimpan answer: $error")
                                    }
                                }, answer)
                            }

                            override fun onCreateFailure(error: String) {
                                onStatus("Gagal membuat answer: $error")
                            }
                        }, org.webrtc.MediaConstraints())
                    }

                    override fun onSetFailure(error: String) {
                        onStatus("Gagal menerima offer: $error")
                    }
                }, offer)
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
            WebRtcSignaling.removeCandidateListener(code, isCamera, it)
        }

        answerListener = null
        offerListener = null
        candidateListener = null

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
