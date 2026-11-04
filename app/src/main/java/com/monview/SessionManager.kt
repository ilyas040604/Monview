
package com.monview

import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
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
        if (code.length != 24) {
            onResult(false, "Kode QR tidak valid")
            return
        }

        FirebaseManager.login { success, uid ->
            if (!success || uid.isBlank()) {
                onResult(false, "Login Firebase gagal")
                return@login
            }

            val updates = mapOf<String, Any>(
                "viewerUid" to uid,
                "joinedAt" to ServerValue.TIMESTAMP,
                "status" to "connected"
            )

            database.getReference("sessions")
                .child(code)
                .updateChildren(updates)
                .addOnSuccessListener {
                    onResult(true, "HP pemantau berhasil bergabung")
                }
                .addOnFailureListener {
                    onResult(
                        false,
                        it.message ?: "Gagal bergabung. QR mungkin kedaluwarsa atau sudah digunakan."
                    )
                }
        }
    }
}
