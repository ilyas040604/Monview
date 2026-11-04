package com.monview

import com.google.firebase.database.ServerValue
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.security.SecureRandom

object SessionManager {

    private val database = Firebase.database(
        "https://monview-3eb52-default-rtdb.asia-southeast1.firebasedatabase.app/"
    )

    private val random = SecureRandom()

    fun createSession(onResult: (Boolean, String) -> Unit) {
        FirebaseManager.login { success, uid ->
            if (!success || uid.isBlank()) {
                onResult(false, "Login Firebase gagal")
                return@login
            }

            val code = buildString {
                repeat(24) {
                    append("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"[random.nextInt(62)])
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
}