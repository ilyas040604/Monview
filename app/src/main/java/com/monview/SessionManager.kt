
package com.monview

import android.content.Context
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.DatabaseReference
import java.security.SecureRandom

object SessionManager {

    private val database = FirebaseDatabase.getInstance(
        "https://monview-3eb52-default-rtdb.asia-southeast1.firebasedatabase.app/"
    )

    private val random = SecureRandom()
    private const val SESSION_LIFETIME_MS = 300_000L

    private fun getServerOffset(
        onResult: (Boolean, Long) -> Unit
    ) {
        database.getReference(".info/serverTimeOffset")
            .addListenerForSingleValueEvent(object : ValueEventListener {

                override fun onDataChange(snapshot: DataSnapshot) {
                    val offset = (snapshot.value as? Number)?.toLong()
                    onResult(offset != null, offset ?: 0L)
                }

                override fun onCancelled(error: DatabaseError) {
                    onResult(false, 0L)
                }
            })
    }

    fun createSession(onResult: (Boolean, String) -> Unit) {
        FirebaseManager.login { success, uid ->
            if (!success || uid.isBlank()) {
                onResult(false, "Login Firebase gagal: $uid")
                return@login
            }

            getServerOffset { timeReady, offset ->
                if (!timeReady) {
                    onResult(false, "Gagal membaca waktu server Firebase.")
                    return@getServerOffset
                }

                val characters =
                    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

                val code = buildString {
                    repeat(24) {
                        append(characters[random.nextInt(characters.length)])
                    }
                }

                val serverNow = System.currentTimeMillis() + offset

                val data = mapOf(
                    "owner" to uid,
                    "createdAt" to ServerValue.TIMESTAMP,
                    "expiresAt" to serverNow + SESSION_LIFETIME_MS,
                    "status" to "waiting"
                )

                database.getReference("sessions").child(code)
                    .setValue(data)
                    .addOnSuccessListener {
                        onResult(true, code)
                    }
                    .addOnFailureListener {
                        onResult(false, "Gagal membuat sesi: ${it.message}")
                    }
            }
        }
    }

    fun joinSession(
        code: String,
        context: Context? = null,
        onResult: (Boolean, String) -> Unit
    ) {
        if (!Regex("^[A-Za-z0-9]{24}$").matches(code)) {
            onResult(false, "Format QR tidak valid.")
            return
        }

        FirebaseManager.login { success, uid ->
            if (!success || uid.isBlank()) {
                onResult(false, "Login Firebase gagal: $uid")
                return@login
            }

            getServerOffset { timeReady, offset ->
                if (!timeReady) {
                    onResult(false, "Gagal membaca waktu server Firebase.")
                    return@getServerOffset
                }

                val serverNow = System.currentTimeMillis() + offset
                val reference = database.getReference("sessions").child(code)

                reference.get()
                    .addOnSuccessListener { snapshot ->
                        if (!snapshot.exists()) {
                            onResult(false, "Sesi QR tidak ditemukan.")
                            return@addOnSuccessListener
                        }

                        val owner = snapshot.child("owner")
                            .getValue(String::class.java)

                        val viewerUid = snapshot.child("viewerUid")
                            .getValue(String::class.java)

                        val expiresAt =
                            (snapshot.child("expiresAt").value as? Number)
                                ?.toLong() ?: 0L

                        when {
                            owner.isNullOrBlank() ->
                                onResult(false, "Data pemilik QR tidak lengkap.")

                            owner == uid ->
                                onResult(false, "Kedua HP memakai akun Firebase yang sama.")

                            !viewerUid.isNullOrBlank() && viewerUid != uid ->
                                onResult(false, "QR sudah digunakan perangkat lain.")

                            expiresAt == 0L ->
                                onResult(false, "Waktu QR tidak valid.")

                            expiresAt <= serverNow ->
                                onResult(false, "QR sudah kedaluwarsa.")

                            else ->
                                claimSession(
                                    reference,
                                    code,
                                    uid,
                                    owner,
                                    context,
                                    offset,
                                    onResult
                                )
                        }
                    }
                    .addOnFailureListener {
                        onResult(false, "Gagal membaca Firebase: ${it.message}")
                    }
            }
        }
    }

    private fun claimSession(
        reference: DatabaseReference,
        code: String,
        uid: String,
        ownerUid: String,
        context: Context?,
        offset: Long,
        onResult: (Boolean, String) -> Unit
    ) {
        var rejectionReason = "QR sudah dipakai perangkat lain."

        reference.runTransaction(object : Transaction.Handler {

            override fun doTransaction(
                currentData: MutableData
            ): Transaction.Result {

                if (currentData.value == null) {
                    rejectionReason = "Sesi QR tidak ditemukan."
                    return Transaction.abort()
                }

                val owner = currentData.child("owner")
                    .getValue(String::class.java)

                val viewerUid = currentData.child("viewerUid")
                    .getValue(String::class.java)

                val expiresAt =
                    (currentData.child("expiresAt").value as? Number)
                        ?.toLong() ?: 0L

                val serverNow = System.currentTimeMillis() + offset

                when {
                    owner != ownerUid -> {
                        rejectionReason = "Identitas pemilik QR berubah."
                        return Transaction.abort()
                    }

                    owner == uid -> {
                        rejectionReason = "Kedua HP memakai akun Firebase yang sama."
                        return Transaction.abort()
                    }

                    !viewerUid.isNullOrBlank() && viewerUid != uid -> {
                        rejectionReason = "QR sudah digunakan perangkat lain."
                        return Transaction.abort()
                    }

                    expiresAt == 0L || expiresAt <= serverNow -> {
                        rejectionReason = "QR sudah kedaluwarsa."
                        return Transaction.abort()
                    }
                }

                currentData.child("viewerUid").value = uid
                currentData.child("joinedAt").value = ServerValue.TIMESTAMP
                currentData.child("status").value = "connected"

                return Transaction.success(currentData)
            }

            override fun onComplete(
                error: DatabaseError?,
                committed: Boolean,
                currentData: DataSnapshot?
            ) {
                when {
                    error != null ->
                        onResult(false, "Firebase menolak pairing: ${error.message}")

                    !committed ->
                        onResult(false, rejectionReason)

                    context != null -> {
                        val saved = PairingStore.save(
                            context,
                            code,
                            "viewer",
                            ownerUid
                        )

                        if (saved) {
                            onResult(true, "Pairing HP pemantau berhasil disimpan.")
                        } else {
                            onResult(false, "Pairing berhasil, tetapi penyimpanan lokal gagal.")
                        }
                    }

                    else ->
                        onResult(true, "HP pemantau berhasil bergabung.")
                }
            }
        }, false)
    }

    fun listenForViewer(
        code: String,
        context: Context? = null,
        onJoined: (Boolean, String) -> Unit
    ): ValueEventListener {

        val reference = database.getReference("sessions/$code")
        var pairingSaved = false

        val listener = object : ValueEventListener {

            override fun onDataChange(snapshot: DataSnapshot) {
                val ownerUid = snapshot.child("owner")
                    .getValue(String::class.java)

                val viewerUid = snapshot.child("viewerUid")
                    .getValue(String::class.java)

                if (ownerUid.isNullOrBlank() ||
                    viewerUid.isNullOrBlank()
                ) return

                if (context != null && !pairingSaved) {
                    pairingSaved = PairingStore.save(
                        context,
                        code,
                        "camera",
                        viewerUid
                    )

                    if (!pairingSaved) {
                        onJoined(false, "Gagal menyimpan pairing HP kamera.")
                        return
                    }
                }

                onJoined(true, "HP pemantau sudah bergabung.")
            }

            override fun onCancelled(error: DatabaseError) {
                onJoined(false, error.message)
            }
        }

        reference.addValueEventListener(listener)
        return listener
    }

    fun removeViewerListener(
        code: String,
        listener: ValueEventListener
    ) {
        database.getReference("sessions/$code")
            .removeEventListener(listener)
    }
}
