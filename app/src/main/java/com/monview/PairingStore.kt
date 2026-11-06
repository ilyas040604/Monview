
package com.monview

import android.content.Context

object PairingStore {

    private const val PREFS_NAME = "monview_pairing"
    private const val KEY_PAIR_ID = "pair_id"
    private const val KEY_ROLE = "pair_role"
    private const val KEY_PEER_UID = "peer_uid"

    data class Pairing(
        val pairId: String,
        val role: String,
        val peerUid: String
    )

    fun save(
        context: Context,
        pairId: String,
        role: String,
        peerUid: String
    ): Boolean {
        if (pairId.isBlank() ||
            peerUid.isBlank() ||
            (role != "camera" && role != "viewer")
        ) return false

        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PAIR_ID, pairId)
            .putString(KEY_ROLE, role)
            .putString(KEY_PEER_UID, peerUid)
            .commit()
    }

    fun get(context: Context): Pairing? {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val pairId = prefs.getString(KEY_PAIR_ID, null)
        val role = prefs.getString(KEY_ROLE, null)
        val peerUid = prefs.getString(KEY_PEER_UID, null)

        if (pairId.isNullOrBlank() ||
            peerUid.isNullOrBlank() ||
            (role != "camera" && role != "viewer")
        ) return null

        return Pairing(pairId, role, peerUid)
    }

    fun isPaired(context: Context): Boolean {
        return get(context) != null
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}
