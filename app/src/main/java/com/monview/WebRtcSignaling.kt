package com.monview

import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.UUID

object WebRtcSignaling {

private val database = FirebaseDatabase.getInstance(  
    "https://monview-3eb52-default-rtdb.asia-southeast1.firebasedatabase.app/"  
)  

// Seluruh komunikasi WebRTC dipisahkan dari data pairing.  
private fun sessionRef(code: String): DatabaseReference {  
    return database.getReference("rtcSessions").child(code)  
}  

private fun connectionRef(  
    code: String,  
    attemptId: String  
): DatabaseReference {  
    return sessionRef(code)  
        .child("connections")  
        .child(attemptId)  
}  

private fun signalsRef(  
    code: String,  
    attemptId: String  
): DatabaseReference {  
    return connectionRef(code, attemptId)  
        .child("signals")  
}  

// MEMBUAT PERMINTAAN KONEKSI DARI HP PEMANTAU  
fun requestConnection(  
    code: String,  
    onResult: (Boolean, String, String) -> Unit  
) {  
    FirebaseManager.login { success, uid ->  

        if (!success || uid.isBlank()) {  
            onResult(  
                false,  
                "",  
                "Login Firebase gagal: $uid"  
            )  
            return@login  
        }  

        val attemptId = sessionRef(code)  
            .child("connections")  
            .push()  
            .key ?: UUID.randomUUID().toString()  

        val data = mapOf(  
            "id" to attemptId,  
            "viewerUid" to uid,  
            "createdAt" to ServerValue.TIMESTAMP  
        )  

        sessionRef(code)  
            .child("activeConnection")  
            .setValue(data)  
            .addOnSuccessListener {  
                onResult(  
                    true,  
                    attemptId,  
                    "Permintaan koneksi terkirim."  
                )  
            }  
            .addOnFailureListener {  
                onResult(  
                    false,  
                    "",  
                    "Gagal meminta koneksi: ${it.message}"  
                )  
            }  
    }  
}  

// MENDENGARKAN PERMINTAAN KONEKSI DI HP KAMERA  
fun listenActiveConnection(  
    code: String,  
    expectedViewerUid: String,  
    onAttempt: (String?) -> Unit  
): ValueEventListener {  

    val reference = sessionRef(code)  
        .child("activeConnection")  

    val listener = object : ValueEventListener {  

        override fun onDataChange(snapshot: DataSnapshot) {  
            val attemptId = snapshot.child("id")  
                .getValue(String::class.java)  

            val viewerUid = snapshot.child("viewerUid")  
                .getValue(String::class.java)  

            if (  
                snapshot.exists() &&  
                viewerUid == expectedViewerUid &&  
                !attemptId.isNullOrBlank()  
            ) {  
                onAttempt(attemptId)  
            } else {  
                onAttempt(null)  
            }  
        }  

        override fun onCancelled(error: DatabaseError) {  
            onAttempt(null)  
        }  
    }  

    reference.addValueEventListener(listener)  
    return listener  
}  

fun removeActiveConnectionListener(  
    code: String,  
    listener: ValueEventListener  
) {  
    sessionRef(code)  
        .child("activeConnection")  
        .removeEventListener(listener)  
}  

// MENGHAPUS PERMINTAAN KONEKSI AKTIF  
fun clearActiveConnection(code: String) {  
    sessionRef(code)  
        .child("activeConnection")  
        .removeValue()  
}  

// MENGIRIM STATUS KAMERA KE PEMANTAU  
fun sendCameraStatus(  
    code: String,  
    message: String  
) {  
    sessionRef(code)  
        .child("cameraStatus")  
        .setValue(  
            mapOf(  
                "message" to message,  
                "updatedAt" to ServerValue.TIMESTAMP  
            )  
        )  
}  

// MENDENGARKAN STATUS KAMERA  
fun listenCameraStatus(  
    code: String,  
    onStatus: (String) -> Unit  
): ValueEventListener {  

    val reference = sessionRef(code)  
        .child("cameraStatus")  

    val listener = object : ValueEventListener {  

        override fun onDataChange(snapshot: DataSnapshot) {  
            val message = snapshot.child("message")  
                .getValue(String::class.java)  

            if (!message.isNullOrBlank()) {  
                onStatus(message)  
            }  
        }  

        override fun onCancelled(error: DatabaseError) {  
            onStatus("STATUS KAMERA TIDAK TERSEDIA")  
        }  
    }  

    reference.addValueEventListener(listener)  
    return listener  
}  

fun removeCameraStatusListener(  
    code: String,  
    listener: ValueEventListener  
) {  
    sessionRef(code)  
        .child("cameraStatus")  
        .removeEventListener(listener)  
}  

// MEMINTA GANTI KAMERA  
fun requestCameraSwitch(  
    code: String,  
    onResult: (Boolean, String) -> Unit  
) {  
    sessionRef(code)  
        .child("cameraControl")  
        .child("switchRequest")  
        .setValue(ServerValue.TIMESTAMP)  
        .addOnSuccessListener {  
            onResult(  
                true,  
                "Perintah ganti kamera terkirim."  
            )  
        }  
        .addOnFailureListener {  
            onResult(  
                false,  
                it.message ?: "Gagal mengirim perintah ganti kamera."  
            )  
        }  
}  

// MENDENGARKAN PERINTAH GANTI KAMERA  
fun listenCameraSwitch(  
    code: String,  
    onRequest: (Long) -> Unit  
): ValueEventListener {  

    val reference = sessionRef(code)  
        .child("cameraControl")  
        .child("switchRequest")  

    val listener = object : ValueEventListener {  

        override fun onDataChange(snapshot: DataSnapshot) {  
            val requestAt =  
                (snapshot.value as? Number)?.toLong()  

            if (requestAt != null) {  
                onRequest(requestAt)  
            }  
        }  

        override fun onCancelled(error: DatabaseError) {  
        }  
    }  

    reference.addValueEventListener(listener)  
    return listener  
}  

fun removeCameraSwitchListener(  
    code: String,  
    listener: ValueEventListener  
) {  
    sessionRef(code)  
        .child("cameraControl")  
        .child("switchRequest")  
        .removeEventListener(listener)  
}  

// MENGIRIM OFFER DARI HP KAMERA  
fun sendOffer(  
    code: String,  
    attemptId: String,  
    offer: SessionDescription,  
    onResult: (Boolean, String) -> Unit  
) {  
    val data = mapOf(  
        "type" to offer.type.canonicalForm(),  
        "sdp" to offer.description,  
        "createdAt" to ServerValue.TIMESTAMP  
    )  

    signalsRef(code, attemptId)  
        .child("offer")  
        .setValue(data)  
        .addOnSuccessListener {  
            onResult(true, "Offer terkirim.")  
        }  
        .addOnFailureListener {  
            onResult(  
                false,  
                it.message ?: "Offer gagal dikirim."  
            )  
        }  
}  

// MENDENGARKAN OFFER DI HP PEMANTAU  
fun listenOffer(  
    code: String,  
    attemptId: String,  
    onOffer: (SessionDescription?) -> Unit  
): ValueEventListener {  

    val reference = signalsRef(code, attemptId)  
        .child("offer")  

    val listener = object : ValueEventListener {  

        override fun onDataChange(snapshot: DataSnapshot) {  
            val type = snapshot.child("type")  
                .getValue(String::class.java)  

            val sdp = snapshot.child("sdp")  
                .getValue(String::class.java)  

            if (type != null && sdp != null) {  
                try {  
                    onOffer(  
                        SessionDescription(  
                            SessionDescription.Type  
                                .fromCanonicalForm(type),  
                            sdp  
                        )  
                    )  
                } catch (_: Exception) {  
                    onOffer(null)  
                }  
            }  
        }  

        override fun onCancelled(error: DatabaseError) {  
            onOffer(null)  
        }  
    }  

    reference.addValueEventListener(listener)  
    return listener  
}  

// MENGIRIM ANSWER DARI HP PEMANTAU  
fun sendAnswer(  
    code: String,  
    attemptId: String,  
    answer: SessionDescription,  
    onResult: (Boolean, String) -> Unit  
) {  
    val data = mapOf(  
        "type" to answer.type.canonicalForm(),  
        "sdp" to answer.description,  
        "createdAt" to ServerValue.TIMESTAMP  
    )  

    signalsRef(code, attemptId)  
        .child("answer")  
        .setValue(data)  
        .addOnSuccessListener {  
            onResult(true, "Answer terkirim.")  
        }  
        .addOnFailureListener {  
            onResult(  
                false,  
                it.message ?: "Answer gagal dikirim."  
            )  
        }  
}  

// MENDENGARKAN ANSWER DI HP KAMERA  
fun listenAnswer(  
    code: String,  
    attemptId: String,  
    onAnswer: (SessionDescription?) -> Unit  
): ValueEventListener {  

    val reference = signalsRef(code, attemptId)  
        .child("answer")  

    val listener = object : ValueEventListener {  

        override fun onDataChange(snapshot: DataSnapshot) {  
            val type = snapshot.child("type")  
                .getValue(String::class.java)  

            val sdp = snapshot.child("sdp")  
                .getValue(String::class.java)  

            if (type != null && sdp != null) {  
                try {  
                    onAnswer(  
                        SessionDescription(  
                            SessionDescription.Type  
                                .fromCanonicalForm(type),  
                            sdp  
                        )  
                    )  
                } catch (_: Exception) {  
                    onAnswer(null)  
                }  
            }  
        }  

        override fun onCancelled(error: DatabaseError) {  
            onAnswer(null)  
        }  
    }  

    reference.addValueEventListener(listener)  
    return listener  
}  

// MENGIRIM ICE CANDIDATE  
fun sendCandidate(  
    code: String,  
    attemptId: String,  
    candidate: IceCandidate,  
    isCamera: Boolean  
) {  
    val path = if (isCamera) {  
        "cameraCandidates"  
    } else {  
        "viewerCandidates"  
    }  

    val data = mapOf(  
        "sdpMid" to candidate.sdpMid,  
        "sdpMLineIndex" to candidate.sdpMLineIndex,  
        "sdp" to candidate.sdp,  
        "createdAt" to ServerValue.TIMESTAMP  
    )  

    signalsRef(code, attemptId)  
        .child(path)  
        .push()  
        .setValue(data)  
}  

// MENDENGARKAN ICE CANDIDATE  
fun listenCandidates(  
    code: String,  
    attemptId: String,  
    isCamera: Boolean,  
    onCandidate: (IceCandidate) -> Unit,  
    onError: (String) -> Unit  
): ChildEventListener {  

    val path = if (isCamera) {  
        "viewerCandidates"  
    } else {  
        "cameraCandidates"  
    }  

    val reference = signalsRef(code, attemptId)  
        .child(path)  

    val listener = object : ChildEventListener {  

        override fun onChildAdded(  
            snapshot: DataSnapshot,  
            previousChildName: String?  
        ) {  
            val mid = snapshot.child("sdpMid")  
                .getValue(String::class.java)  

            val index = (  
                snapshot.child("sdpMLineIndex").value as? Number  
            )?.toInt()  

            val sdp = snapshot.child("sdp")  
                .getValue(String::class.java)  

            if (  
                mid != null &&  
                index != null &&  
                sdp != null  
            ) {  
                onCandidate(  
                    IceCandidate(mid, index, sdp)  
                )  
            }  
        }  

        override fun onChildChanged(  
            snapshot: DataSnapshot,  
            previousChildName: String?  
        ) {  
        }  

        override fun onChildRemoved(  
            snapshot: DataSnapshot  
        ) {  
        }  

        override fun onChildMoved(  
            snapshot: DataSnapshot,  
            previousChildName: String?  
        ) {  
        }  

        override fun onCancelled(error: DatabaseError) {  
            onError(error.message)  
        }  
    }  

    reference.addChildEventListener(listener)  
    return listener  
}  

// MENGHAPUS LISTENER ANSWER  
fun removeAnswerListener(  
    code: String,  
    attemptId: String,  
    listener: ValueEventListener  
) {  
    signalsRef(code, attemptId)  
        .child("answer")  
        .removeEventListener(listener)  
}  

// MENGHAPUS LISTENER OFFER  
fun removeOfferListener(  
    code: String,  
    attemptId: String,  
    listener: ValueEventListener  
) {  
    signalsRef(code, attemptId)  
        .child("offer")  
        .removeEventListener(listener)  
}  

// MENGHAPUS LISTENER ICE CANDIDATE  
fun removeCandidateListener(  
    code: String,  
    attemptId: String,  
    isCamera: Boolean,  
    listener: ChildEventListener  
) {  
    val path = if (isCamera) {  
        "viewerCandidates"  
    } else {  
        "cameraCandidates"  
    }  

    signalsRef(code, attemptId)  
        .child(path)  
        .removeEventListener(listener)  
}

}