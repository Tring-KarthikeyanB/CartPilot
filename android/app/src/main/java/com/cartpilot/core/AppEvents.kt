package com.cartpilot.core

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A PayPal hand-off back into the app via cartpilot://payment/{return|cancel}?order_id=... */
data class PaymentRedirect(val kind: Kind, val orderId: String?) {
    enum class Kind { Return, Cancel }

    companion object {
        fun parse(uri: Uri?): PaymentRedirect? {
            if (uri == null || uri.scheme != "cartpilot" || uri.host != "payment") return null
            val kind = when (uri.lastPathSegment) {
                "return" -> Kind.Return
                "cancel" -> Kind.Cancel
                else -> return null
            }
            val orderId = uri.getQueryParameter("order_id")?.takeIf { it.matches(Regex("^[A-Z0-9]{5,40}$")) }
            return PaymentRedirect(kind, orderId)
        }
    }
}

/**
 * App-wide one-shot events. A redirect is held until a consumer takes it, so it survives the
 * activity being recreated (or cold-started) by the deep link before the checkout screen exists.
 */
class AppEvents {
    private val _paymentRedirect = MutableStateFlow<PaymentRedirect?>(null)
    val paymentRedirect: StateFlow<PaymentRedirect?> = _paymentRedirect.asStateFlow()

    /** A prompt another screen wants the chat to send (e.g. "Optimize my cart"). */
    private val _pendingPrompt = MutableStateFlow<String?>(null)
    val pendingPrompt: StateFlow<String?> = _pendingPrompt.asStateFlow()

    fun postPaymentRedirect(redirect: PaymentRedirect) {
        _paymentRedirect.value = redirect
    }

    fun consumePaymentRedirect(): PaymentRedirect? = _paymentRedirect.value.also { _paymentRedirect.value = null }

    fun postPrompt(prompt: String) {
        _pendingPrompt.value = prompt
    }

    fun consumePrompt(): String? = _pendingPrompt.value.also { _pendingPrompt.value = null }
}
