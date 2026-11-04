package com.monview

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import java.security.SecureRandom

object SessionManager {

    private val database = FirebaseDatabase.getInstance(
        "https://monview-3eb52-default-rtdb.asia-southeast1.firebasedatabase.app/"
    )

    private val random = SecureRandom()

    fun createSession(onResult: (Boolean, String) -> Unit) {
        FirebaseManager.login { success, uid ->
            if (!success || uid.isBlank()) {
                onResult(false, "Login Firebase gagal")
                return@login
            }

            val characters =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

            val code = buildString {
                repeat(24) {
                    append(characters[random.nextInt(characters.length)])
                }
            }

            val data = mapOf(
                "owner" to uid,
                "createdAt" to ServerValue.TIMESTAMP,
                "expiresAt" to System.currentTimeMillis() + 60_000,
                "status" to "waiting"
            )

            database.getReference("sessions")
                .child(code)
                .setValue(data)
                .addOnSuccessListener {
                    onResult(true, code)
                }
                .addOnFailureListener {
                    onResult(false, it.message ?: "Gagal membuat sesi")
                }
        }
    }

    fun joinSession(code: String, onResult: (Boolean, String) -> Unit) {
        if (!Regex("^[A-Za-z0-9]{24}$").matches(code)) {
            onResult(false, "Kode QR tidak valid")
            return
        }

        FirebaseManager.login { success, uid ->
            if (!success || uid.isBlank()) {
                onResult(false, "Login Firebase gagal")
                return@login
            }

            val reference = database.getReference("sessions").child(code)

            reference.runTransaction(object : Transaction.Handler {

                override fun doTransaction(
                    currentData: MutableData
                ): Transaction.Result {

                    if (currentData.value == null) {
                        return Transaction.abort()
                    }

                    val expiresAt =
                        currentData.child("expiresAt")
                            .getValue(Long::class.java) ?: 0L

                    val owner =
                        currentData.child("owner")
                            .getValue(String::class.java)

                    val viewerUid =
                        currentData.child("viewerUid")
                            .getValue(String::class.java)

                    if (expiresAt <= System.currentTimeMillis() ||
                        !viewerUid.isNullOrBlank() ||
                        owner == uid
                    ) {
                        return Transaction.abort()
                    }

                    currentData.child("viewerUid").value = uid
                    currentData.child("joinedAt").value =
                        System.currentTimeMillis()
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
                            onResult(false, error.message)

                        committed ->
                            onResult(true, "HP pemantau berhasil bergabung")

                        else ->
                            onResult(
                                false,
                                "QR tidak valid, kedaluwarsa, atau sudah digunakan."
                            )
                    }
                }
            })
        }
    }

    fun listenForViewer(
        code: String,
        onJoined: (Boolean, String) -> Unit
    ): ValueEventListener {

        val reference = database.getReference("sessions/$code")

        val listener = object : ValueEventListener {

            override fun onDataChange(snapshot: DataSnapshot) {
                val viewerUid =
                    snapshot.child("viewerUid")
                        .getValue(String::class.java)

                if (!viewerUid.isNullOrBlank()) {
                    onJoined(true, "HP pemantau sudah bergabung")
                }
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