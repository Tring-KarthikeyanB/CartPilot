package com.cartpilot.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.UUID

/** Persists the anonymous shopping session id and any PayPal order awaiting approval. */
interface SessionStore {
    val sessionId: String
    var pendingOrderId: String?
}

class PrefsSessionStore(context: Context) : SessionStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("cartpilot_session", Context.MODE_PRIVATE)

    override val sessionId: String by lazy {
        prefs.getString(KEY_SESSION, null) ?: ("android-" + UUID.randomUUID().toString().replace("-", "")).also {
            prefs.edit { putString(KEY_SESSION, it) }
        }
    }

    override var pendingOrderId: String?
        get() = prefs.getString(KEY_PENDING_ORDER, null)
        set(value) {
            prefs.edit { if (value == null) remove(KEY_PENDING_ORDER) else putString(KEY_PENDING_ORDER, value) }
        }

    private companion object {
        const val KEY_SESSION = "session_id"
        const val KEY_PENDING_ORDER = "pending_order_id"
    }
}
