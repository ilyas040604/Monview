package com.monview

import android.os.Handler
import android.os.Looper
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
    private val attemptId: String,
    private val isCamera: Boolean,
    private val onRemoteTrack: (VideoTrack) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onRemoteAudioTrack: (AudioTrack) -> Unit = {}
) {

    companion object {
        private const val DISCONNECT_GRACE_MS = 8_000L
    }

    private var peerConnection: PeerConnection? = null

    private var answerListener: ValueEventListener? = null
    private var offerListener: ValueEventListener? = null
    private var candidateListener: ChildEventListener? = null

    private val pendingCandidates = mutableListOf<IceCandidate>()
    private val deliveredTrackIds = mutableSetOf<String>()

    private val stateHandler = Handler(Looper.getMainLooper())

    private var disconnectGraceRunnable: Runnable? = null

    @Volatile
    private var iceDisconnected = false

    @Volatile
    private var peerDisconnected = false

    @Volatile
    private var remoteDescriptionReady = false

    @Volatile
    private var offerProcessing = false

    @Volatile
    private var answerProcessing = false

    @Volatile
    private var stopped = false

    private fun deliverTrack(track: MediaStreamTrack?) {
        if (stopped) return

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

    /*
     * Menangani ICE DISCONNECTED.
     *
     * Tidak langsung mematikan WebRTC.
     * Berikan kesempatan koneksi pulih selama 8 detik.
     */
    private fun handleIceConnectionState(
        state: PeerConnection.IceConnectionState
    ) {

        if (stopped) return

        when (state) {

            PeerConnection.IceConnectionState.DISCONNECTED -> {

                iceDisconnected = true

                onStatus(
                    "JARINGAN TERGANGGU — MENUNGGU PEMULIHAN"
                )

                scheduleDisconnectGrace()
            }

            PeerConnection.IceConnectionState.FAILED -> {

                iceDisconnected = true
                cancelDisconnectGrace()

                onStatus("ICE: FAILED")
            }

            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> {

                iceDisconnected = false

                onStatus("ICE: $state")

                cancelDisconnectGraceIfRecovered()
            }

            else -> {
                onStatus("ICE: $state")
            }
        }
    }

    /*
     * Menangani status PeerConnection.
     *
     * Status DISCONNECTED diberi waktu pemulihan.
     * Status FAILED tetap langsung dilaporkan.
     */
    private fun handlePeerConnectionState(
        state: PeerConnection.PeerConnectionState
    ) {

        if (stopped) return

        when (state) {

            PeerConnection.PeerConnectionState.DISCONNECTED -> {

                peerDisconnected = true

                onStatus(
                    "JARINGAN TERGANGGU — MENUNGGU PEMULIHAN"
                )

                scheduleDisconnectGrace()
            }

            PeerConnection.PeerConnectionState.FAILED -> {

                peerDisconnected = true
                cancelDisconnectGrace()

                onStatus("WEBRTC: FAILED")
            }

            PeerConnection.PeerConnectionState.CONNECTED -> {

                peerDisconnected = false

                onStatus("WEBRTC: CONNECTED")

                cancelDisconnectGraceIfRecovered()
            }

            else -> {
                onStatus("WEBRTC: $state")
            }
        }
    }

    /*
     * Jalankan sambung ulang hanya jika status terputus
     * masih bertahan setelah 8 detik.
     */
    private fun scheduleDisconnectGrace() {

        if (stopped || disconnectGraceRunnable != null) {
            return
        }

        val task = Runnable {

            disconnectGraceRunnable = null

            if (stopped) return@Runnable

            when {

                iceDisconnected -> {
                    onStatus("ICE: DISCONNECTED")
                }

                peerDisconnected -> {
                    onStatus("WEBRTC: DISCONNECTED")
                }
            }
        }

        disconnectGraceRunnable = task

        stateHandler.postDelayed(
            task,
            DISCONNECT_GRACE_MS
        )
    }

    /*
     * Batalkan timer jika kedua status sudah pulih.
     */
    private fun cancelDisconnectGraceIfRecovered() {

        if (
            !iceDisconnected &&
            !peerDisconnected
        ) {
            cancelDisconnectGrace()
        }
    }

    private fun cancelDisconnectGrace() {

        disconnectGraceRunnable?.let {
            stateHandler.removeCallbacks(it)
        }

        disconnectGraceRunnable = null
    }

    private val observer = object : PeerConnection.Observer {

        override fun onSignalingChange(
            state: PeerConnection.SignalingState
        ) {
            if (!stopped) {
                onStatus("SIGNALING: $state")
            }
        }

        override fun onIceConnectionChange(
            state: PeerConnection.IceConnectionState
        ) {
            handleIceConnectionState(state)
        }

        override fun onConnectionChange(
            state: PeerConnection.PeerConnectionState
        ) {
            handlePeerConnectionState(state)
        }

        override fun onIceConnectionReceivingChange(
            receiving: Boolean
        ) {

            if (!stopped) {
                onStatus(
                    if (receiving) {
                        "MENERIMA DATA ICE"
                    } else {
                        "BELUM MENERIMA DATA ICE"
                    }
                )
            }
        }

        override fun onIceGatheringChange(
            state: PeerConnection.IceGatheringState
        ) {
            if (!stopped) {
                onStatus("ICE GATHERING: $state")
            }
        }

        override fun onIceCandidate(
            candidate: IceCandidate
        ) {

            if (!stopped) {
                WebRtcSignaling.sendCandidate(
                    code,
                    attemptId,
                    candidate,
                    isCamera
                )
            }
        }

        override fun onIceCandidatesRemoved(
            candidates: Array<IceCandidate>
        ) {
        }

        override fun onAddStream(
            stream: MediaStream
        ) {

            if (stopped) return

            stream.videoTracks.firstOrNull()?.let {
                deliverTrack(it)
            }

            stream.audioTracks.firstOrNull()?.let {
                deliverTrack(it)
            }
        }

        override fun onRemoveStream(
            stream: MediaStream
        ) {
        }

        override fun onDataChannel(
            dataChannel: org.webrtc.DataChannel
        ) {
        }

        override fun onRenegotiationNeeded() {

            if (!stopped) {
                onStatus("NEGOSIASI ULANG DIPERLUKAN")
            }
        }

        override fun onAddTrack(
            receiver: RtpReceiver,
            mediaStreams: Array<MediaStream>
        ) {

            if (!stopped) {
                deliverTrack(receiver.track())
            }
        }

        override fun onTrack(
            transceiver: RtpTransceiver
        ) {

            if (!stopped) {
                deliverTrack(
                    transceiver.receiver.track()
                )
            }
        }
    }

    /*
     * Memasukkan ICE candidate.
     *
     * Jika SDP belum siap, candidate disimpan terlebih dahulu.
     */
    private fun addRemoteCandidate(
        candidate: IceCandidate
    ) {

        if (stopped) return

        val connection = peerConnection ?: return

        if (remoteDescriptionReady) {

            val accepted =
                connection.addIceCandidate(candidate)

            if (!accepted) {
                onStatus(
                    "ICE CANDIDATE GAGAL DITAMBAHKAN"
                )
            }

        } else {

            pendingCandidates.add(candidate)
        }
    }

    /*
     * Memproses candidate yang sebelumnya tertunda.
     */
    private fun flushPendingCandidates() {

        if (stopped) return

        val connection = peerConnection ?: return

        remoteDescriptionReady = true

        val candidates = pendingCandidates.toList()

        pendingCandidates.clear()

        var acceptedCount = 0

        candidates.forEach { candidate ->

            if (
                connection.addIceCandidate(candidate)
            ) {
                acceptedCount++
            }
        }

        onStatus(
            "REMOTE DESCRIPTION SIAP — " +
                "$acceptedCount/${candidates.size} ICE DITERIMA"
        )
    }

    /*
     * Listener ICE candidate.
     */
    private fun createCandidateListener() {

        candidateListener =
            WebRtcSignaling.listenCandidates(
                code,
                attemptId,
                isCamera,

                { candidate ->
                    addRemoteCandidate(candidate)
                },

                { error ->
                    if (!stopped) {
                        onStatus(
                            "ICE LISTENER ERROR: $error"
                        )
                    }
                }
            )
    }

    /*
     * MEMULAI WEBRTC DI HP KAMERA
     */
    fun startCamera(
        stream: CameraStream
    ) {

        if (
            !isCamera ||
            peerConnection != null ||
            stopped
        ) {
            return
        }

        onStatus(
            "MENYIAPKAN PEER CONNECTION KAMERA"
        )

        WebRtcManager.createPeerConnection(
            observer
        ) { connection, error ->

            if (stopped) {

                connection?.close()
                connection?.dispose()

                return@createPeerConnection
            }

            if (connection == null) {

                onStatus(
                    "GAGAL MEMBUAT PEER CONNECTION: " +
                        "${error ?: "ERROR TIDAK DIKETAHUI"}"
                )

                return@createPeerConnection
            }

            peerConnection = connection

            val video = stream.videoTrack
            val audio = stream.audioTrack

            if (
                video == null ||
                audio == null
            ) {

                onStatus(
                    "TRACK VIDEO ATAU AUDIO KOSONG"
                )

                return@createPeerConnection
            }

            video.setEnabled(true)
            audio.setEnabled(true)

            val videoSender = connection.addTrack(
                video,
                listOf("monview_stream")
            )

            val audioSender = connection.addTrack(
                audio,
                listOf("monview_stream")
            )

            if (
                videoSender == null ||
                audioSender == null
            ) {

                onStatus(
                    "GAGAL MENAMBAHKAN TRACK VIDEO/AUDIO"
                )

                return@createPeerConnection
            }

            onStatus(
                "TRACK VIDEO DAN AUDIO SIAP"
            )

            createCandidateListener()

            answerListener =
                WebRtcSignaling.listenAnswer(
                    code,
                    attemptId
                ) { answer ->

                    if (
                        stopped ||
                        answer == null ||
                        answerProcessing
                    ) {
                        return@listenAnswer
                    }

                    if (
                        connection.signalingState() !=
                        PeerConnection.SignalingState.HAVE_LOCAL_OFFER
                    ) {
                        return@listenAnswer
                    }

                    answerProcessing = true

                    onStatus(
                        "ANSWER DITERIMA — MEMPROSES SDP"
                    )

                    connection.setRemoteDescription(
                        object : SimpleSdpObserver() {

                            override fun onSetSuccess() {

                                if (stopped) return

                                answerProcessing = false

                                flushPendingCandidates()

                                onStatus(
                                    "ANSWER BERHASIL DIPROSES KAMERA"
                                )
                            }

                            override fun onSetFailure(
                                error: String
                            ) {

                                answerProcessing = false

                                if (!stopped) {
                                    onStatus(
                                        "ANSWER GAGAL DIPROSES: $error"
                                    )
                                }
                            }
                        },
                        answer
                    )
                }

            connection.createOffer(
                object : SimpleSdpObserver() {

                    override fun onCreateSuccess(
                        description: SessionDescription
                    ) {

                        if (stopped) return

                        onStatus(
                            "OFFER BERHASIL DIBUAT"
                        )

                        connection.setLocalDescription(
                            object : SimpleSdpObserver() {

                                override fun onSetSuccess() {

                                    if (stopped) return

                                    onStatus(
                                        "LOCAL OFFER SIAP"
                                    )

                                    WebRtcSignaling.sendOffer(
                                        code,
                                        attemptId,
                                        description
                                    ) { success, message ->

                                        if (!stopped) {

                                            onStatus(
                                                if (success) {
                                                    "OFFER TERKIRIM"
                                                } else {
                                                    "OFFER GAGAL: $message"
                                                }
                                            )
                                        }
                                    }
                                }

                                override fun onSetFailure(
                                    error: String
                                ) {

                                    if (!stopped) {
                                        onStatus(
                                            "LOCAL OFFER GAGAL: $error"
                                        )
                                    }
                                }
                            },
                            description
                        )
                    }

                    override fun onCreateFailure(
                        error: String
                    ) {

                        if (!stopped) {
                            onStatus(
                                "GAGAL MEMBUAT OFFER: $error"
                            )
                        }
                    }
                },
                MediaConstraints()
            )
        }
    }

    /*
     * MEMULAI WEBRTC DI HP PEMANTAU
     */
    fun startViewer() {

        if (
            isCamera ||
            peerConnection != null ||
            stopped
        ) {
            return
        }

        onStatus(
            "MENYIAPKAN PEER CONNECTION PEMANTAU"
        )

        WebRtcManager.createPeerConnection(
            observer
        ) { connection, error ->

            if (stopped) {

                connection?.close()
                connection?.dispose()

                return@createPeerConnection
            }

            if (connection == null) {

                onStatus(
                    "GAGAL MEMBUAT PEER CONNECTION: " +
                        "${error ?: "ERROR TIDAK DIKETAHUI"}"
                )

                return@createPeerConnection
            }

            peerConnection = connection

            onStatus(
                "MENUNGGU OFFER KAMERA"
            )

            createCandidateListener()

            offerListener =
                WebRtcSignaling.listenOffer(
                    code,
                    attemptId
                ) { offer ->

                    if (
                        stopped ||
                        offer == null ||
                        offerProcessing ||
                        connection.signalingState() !=
                        PeerConnection.SignalingState.STABLE
                    ) {
                        return@listenOffer
                    }

                    offerProcessing = true

                    onStatus(
                        "OFFER DITERIMA — MEMPROSES SDP"
                    )

                    connection.setRemoteDescription(
                        object : SimpleSdpObserver() {

                            override fun onSetSuccess() {

                                if (stopped) return

                                flushPendingCandidates()

                                onStatus(
                                    "OFFER BERHASIL DIPROSES"
                                )

                                connection.createAnswer(
                                    object : SimpleSdpObserver() {

                                        override fun onCreateSuccess(
                                            answer: SessionDescription
                                        ) {

                                            if (stopped) return

                                            onStatus(
                                                "ANSWER BERHASIL DIBUAT"
                                            )

                                            connection.setLocalDescription(
                                                object : SimpleSdpObserver() {

                                                    override fun onSetSuccess() {

                                                        if (stopped) return

                                                        WebRtcSignaling.sendAnswer(
                                                            code,
                                                            attemptId,
                                                            answer
                                                        ) { success, message ->

                                                            if (!stopped) {

                                                                onStatus(
                                                                    if (success) {
                                                                        "ANSWER TERKIRIM"
                                                                    } else {
                                                                        "ANSWER GAGAL: $message"
                                                                    }
                                                                )
                                                            }
                                                        }
                                                    }

                                                    override fun onSetFailure(
                                                        error: String
                                                    ) {

                                                        if (!stopped) {
                                                            onStatus(
                                                                "LOCAL ANSWER GAGAL: $error"
                                                            )
                                                        }
                                                    }
                                                },
                                                answer
                                            )
                                        }

                                        override fun onCreateFailure(
                                            error: String
                                        ) {

                                            if (!stopped) {
                                                onStatus(
                                                    "GAGAL MEMBUAT ANSWER: $error"
                                                )
                                            }
                                        }
                                    },
                                    MediaConstraints()
                                )
                            }

                            override fun onSetFailure(
                                error: String
                            ) {

                                offerProcessing = false

                                if (!stopped) {
                                    onStatus(
                                        "OFFER GAGAL DIPROSES: $error"
                                    )
                                }
                            }
                        },
                        offer
                    )
                }
        }
    }

    /*
     * MENGHENTIKAN SELURUH SESI WEBRTC
     */
    fun stop() {

        if (stopped) return

        stopped = true

        cancelDisconnectGrace()

        answerListener?.let { listener ->

            WebRtcSignaling.removeAnswerListener(
                code,
                attemptId,
                listener
            )
        }

        offerListener?.let { listener ->

            WebRtcSignaling.removeOfferListener(
                code,
                attemptId,
                listener
            )
        }

        candidateListener?.let { listener ->

            WebRtcSignaling.removeCandidateListener(
                code,
                attemptId,
                isCamera,
                listener
            )
        }

        answerListener = null
        offerListener = null
        candidateListener = null

        pendingCandidates.clear()
        deliveredTrackIds.clear()

        remoteDescriptionReady = false
        offerProcessing = false
        answerProcessing = false

        iceDisconnected = false
        peerDisconnected = false

        try {
            peerConnection?.close()
        } catch (_: Exception) {
        }

        try {
            peerConnection?.dispose()
        } catch (_: Exception) {
        }

        peerConnection = null

        onStatus(
            "WEBRTC SESSION DIHENTIKAN"
        )
    }
}

open class SimpleSdpObserver : SdpObserver {

    override fun onCreateSuccess(
        description: SessionDescription
    ) {
    }

    override fun onSetSuccess() {
    }

    override fun onCreateFailure(
        error: String
    ) {
    }

    override fun onSetFailure(
        error: String
    ) {
    }
}