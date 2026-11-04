
package com.monview

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.ServerValue
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

object WebRtcSignaling {

    private val database = FirebaseDatabase.getInstance(
        "https://monview-3eb52-default-rtdb.asia-southeast1.firebasedatabase.app/"
    )

    fun sendOffer(
        code: String,
        offer: SessionDescription,
        onResult: (Boolean, String) -> Unit
    ) {
        val data = mapOf(
            "type" to offer.type.canonicalForm(),
            "sdp" to offer.description
        )

        database.getReference("sessions/$code/signals/offer")
            .setValue(data)
            .addOnSuccessListener {
                onResult(true, "Offer terkirim")
            }
            .addOnFailureListener {
                onResult(false, it.message ?: "Offer gagal dikirim")
            }
    }

    fun listenOffer(
        code: String,
        onOffer: (SessionDescription?) -> Unit
    ): ValueEventListener {
        val reference =
            database.getReference("sessions/$code/signals/offer")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val type = snapshot.child("type").getValue(String::class.java)
                val sdp = snapshot.child("sdp").getValue(String::class.java)

                if (type != null && sdp != null) {
                    onOffer(
                        SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(type),
                            sdp
                        )
                    )
                }
            }

            override fun onCancelled(error: DatabaseError) {
                onOffer(null)
            }
        }

        reference.addValueEventListener(listener)
        return listener
    }

    fun sendAnswer(
        code: String,
        answer: SessionDescription,
        onResult: (Boolean, String) -> Unit
    ) {
        val data = mapOf(
            "type" to answer.type.canonicalForm(),
            "sdp" to answer.description
        )

        database.getReference("sessions/$code/signals/answer")
            .setValue(data)
            .addOnSuccessListener {
                onResult(true, "Answer terkirim")
            }
            .addOnFailureListener {
                onResult(false, it.message ?: "Answer gagal dikirim")
            }
    }

    fun listenAnswer(
        code: String,
        onAnswer: (SessionDescription?) -> Unit
    ): ValueEventListener {
        val reference =
            database.getReference("sessions/$code/signals/answer")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val type = snapshot.child("type").getValue(String::class.java)
                val sdp = snapshot.child("sdp").getValue(String::class.java)

                if (type != null && sdp != null) {
                    onAnswer(
                        SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(type),
                            sdp
                        )
                    )
                }
            }

            override fun onCancelled(error: DatabaseError) {
                onAnswer(null)
            }
        }

        reference.addValueEventListener(listener)
        return listener
    }

    fun sendCandidate(
        code: String,
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

        database.getReference("sessions/$code/signals/$path")
            .push()
            .setValue(data)
    }

    fun listenCandidates(
        code: String,
        isCamera: Boolean,
        onCandidate: (IceCandidate) -> Unit
    ): ChildEventListener {
        val path = if (isCamera) {
            "viewerCandidates"
        } else {
            "cameraCandidates"
        }

        val reference =
            database.getReference("sessions/$code/signals/$path")

        val listener = object : ChildEventListener {
            override fun onChildAdded(
                snapshot: DataSnapshot,
                previousChildName: String?
            ) {
                val mid = snapshot.child("sdpMid")
                    .getValue(String::class.java)
                val index = snapshot.child("sdpMLineIndex")
                    .getValue(Int::class.java)
                val sdp = snapshot.child("sdp")
                    .getValue(String::class.java)

                if (mid != null && index != null && sdp != null) {
                    onCandidate(IceCandidate(mid, index, sdp))
                }
            }

            override fun onChildChanged(
                snapshot: DataSnapshot,
                previousChildName: String?
            ) {}

            override fun onChildRemoved(snapshot: DataSnapshot) {}

            override fun onChildMoved(
                snapshot: DataSnapshot,
                previousChildName: String?
            ) {}

            override fun onCancelled(error: DatabaseError) {}
        }

        reference.addChildEventListener(listener)
        return listener
    }
}
