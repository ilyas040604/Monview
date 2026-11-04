package com.monview

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

object FirebaseManager {

    private val auth = FirebaseAuth.getInstance()

    private val database = FirebaseDatabase.getInstance(
        "https://monview-3eb52-default-rtdb.asia-southeast1.firebasedatabase.app/"
    )

    fun login(onResult: (Boolean, String) -> Unit) {
        if (auth.currentUser != null) {
            onResult(true, auth.currentUser!!.uid)
            return
        }

        auth.signInAnonymously()
            .addOnSuccessListener {
                onResult(true, it.user?.uid ?: "")
            }
            .addOnFailureListener {
                onResult(false, it.message ?: "Login Firebase gagal")
            }
    }
}