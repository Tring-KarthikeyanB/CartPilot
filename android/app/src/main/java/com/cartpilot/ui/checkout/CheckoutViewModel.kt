package com.cartpilot.ui.checkout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cartpilot.core.AppContainer
import com.cartpilot.core.AppEvents
import com.cartpilot.core.AppResult
import com.cartpilot.core.PaymentRedirect
import com.cartpilot.core.formatUsd
import com.cartpilot.domain.model.Cart
import com.cartpilot.domain.repository.CartRepository
import com.cartpilot.domain.repository.PaymentRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface CheckoutStage {
    data object Review : CheckoutStage
    data object CreatingOrder : CheckoutStage
    data class AwaitingApproval(val orderId: String, val approveUrl: String?, val checking: Boolean = false) : CheckoutStage
    data class Capturing(val orderId: String) : CheckoutStage
    data class NotCompleted(
        val orderId: String,
        val approveUrl: String?,
        val title: String,
        val message: String,
        val canCheckAgain: Boolean = false,
    ) : CheckoutStage
    data class Failed(val message: String, val retryCaptureOf: String? = null) : CheckoutStage
}

sealed interface CheckoutEffect {
    data class OpenUrl(val url: String) : CheckoutEffect
    data class Completed(val orderId: String) : CheckoutEffect
}

data class CheckoutUiState(
    val cart: Cart? = null,
    val loadingCart: Boolean = true,
    val approved: Boolean = false,
    val stage: CheckoutStage = CheckoutStage.Review,
    val notice: String? = null,
) {
    val canPay: Boolean
        get() = approved && stage == CheckoutStage.Review && cart != null && !cart.isEmpty && !loadingCart
}

/**
 * Payment rules mirrored from the backend: the amount comes from the server cart; the user must
 * explicitly approve; success is shown only after the backend captured AND verified the PayPal order.
 * A deep link back from PayPal only triggers that verification, it is never treated as payment.
 */
class CheckoutViewModel(
    private val carts: CartRepository,
    private val payments: PaymentRepository,
    private val events: AppEvents,
) : ViewModel() {

    private val _state = MutableStateFlow(CheckoutUiState(cart = carts.cart.value))
    val state: StateFlow<CheckoutUiState> = _state.asStateFlow()

    private val _effects = Channel<CheckoutEffect>(Channel.BUFFERED)
    val effects: Flow<CheckoutEffect> = _effects.receiveAsFlow()

    private var captureInFlight = false

    init {
        viewModelScope.launch { carts.cart.collect { c -> _state.update { it.copy(cart = c) } } }
        viewModelScope.launch {
            carts.refresh()
            _state.update { it.copy(loadingCart = false) }
        }
        val pending = payments.pendingOrderId
        val hasRedirect = events.paymentRedirect.value != null
        if (pending != null) {
            _state.update { it.copy(stage = CheckoutStage.AwaitingApproval(pending, null)) }
            if (!hasRedirect) checkStatus(pending) // e.g. app was killed while the buyer was on PayPal
        }
        viewModelScope.launch {
            events.paymentRedirect.filterNotNull().collect {
                events.consumePaymentRedirect()?.let(::handleRedirect)
            }
        }
    }

    // ---------- review ----------

    fun setApproved(approved: Boolean) = _state.update { it.copy(approved = approved) }

    fun pay() {
        val s = _state.value
        val cart = s.cart ?: return
        if (!s.canPay) return
        _state.update { it.copy(stage = CheckoutStage.CreatingOrder) }
        viewModelScope.launch {
            when (val r = payments.createOrder(expectedTotal = cart.subtotal)) {
                is AppResult.Success -> {
                    _state.update { it.copy(stage = CheckoutStage.AwaitingApproval(r.data.orderId, r.data.approveUrl)) }
                    _effects.send(CheckoutEffect.OpenUrl(r.data.approveUrl))
                }
                is AppResult.Failure -> when (r.code) {
                    "cart_changed", "out_of_stock", "cart_empty" -> {
                        carts.refresh()
                        _state.update { it.copy(stage = CheckoutStage.Review, approved = false, notice = r.message) }
                    }
                    else -> _state.update { it.copy(stage = CheckoutStage.Failed(r.message)) }
                }
            }
        }
    }

    // ---------- returning from PayPal ----------

    private fun handleRedirect(redirect: PaymentRedirect) {
        val orderId = redirect.orderId ?: currentOrderId() ?: payments.pendingOrderId ?: return
        when (redirect.kind) {
            PaymentRedirect.Kind.Return -> capture(orderId)
            PaymentRedirect.Kind.Cancel -> _state.update {
                it.copy(
                    stage = CheckoutStage.NotCompleted(
                        orderId, approveUrlOf(it.stage), "Payment cancelled", "You cancelled in PayPal. No money was taken.",
                    ),
                )
            }
        }
    }

    /** Fallback when the deep link never arrives (tab closed, redirect blocked): ask the backend. */
    fun onResumed() {
        val stage = _state.value.stage as? CheckoutStage.AwaitingApproval ?: return
        if (stage.checking || captureInFlight) return
        viewModelScope.launch {
            delay(700) // let a just-delivered deep link win the race
            val current = _state.value.stage as? CheckoutStage.AwaitingApproval ?: return@launch
            if (!captureInFlight && current.orderId == stage.orderId) checkStatus(stage.orderId)
        }
    }

    fun checkAgain() {
        currentOrderId()?.let(::checkStatus)
    }

    private fun checkStatus(orderId: String) {
        _state.update { it.copy(stage = CheckoutStage.AwaitingApproval(orderId, approveUrlOf(it.stage), checking = true)) }
        viewModelScope.launch {
            when (val r = payments.status(orderId)) {
                is AppResult.Success -> {
                    val status = r.data
                    when {
                        status.isCompleted -> complete(orderId)
                        status.canCapture -> capture(orderId)
                        status.paypalStatus == "VOIDED" -> {
                            payments.clearPendingOrder()
                            _state.update { it.copy(stage = CheckoutStage.Failed("This PayPal order expired. Please start checkout again.")) }
                        }
                        else -> _state.update {
                            it.copy(
                                stage = CheckoutStage.NotCompleted(
                                    orderId, status.approveUrl, "Payment not completed",
                                    "You haven't approved the payment in PayPal yet. Nothing has been charged.",
                                    canCheckAgain = true,
                                ),
                            )
                        }
                    }
                }
                is AppResult.Failure -> {
                    if (r.code == "payment_not_found") payments.clearPendingOrder()
                    _state.update {
                        it.copy(
                            stage = if (r.code == "payment_not_found") CheckoutStage.Failed("We couldn't find this payment. Please start checkout again.")
                            else CheckoutStage.NotCompleted(orderId, approveUrlOf(it.stage), "Couldn't check payment", r.message, canCheckAgain = true),
                        )
                    }
                }
            }
        }
    }

    fun retryCapture(orderId: String) = capture(orderId)

    private fun capture(orderId: String) {
        if (captureInFlight) return
        captureInFlight = true
        val approveUrl = approveUrlOf(_state.value.stage)
        _state.update { it.copy(stage = CheckoutStage.Capturing(orderId)) }
        viewModelScope.launch {
            val r = payments.capture(orderId)
            captureInFlight = false
            when (r) {
                is AppResult.Success -> complete(orderId)
                is AppResult.Failure -> _state.update {
                    it.copy(
                        stage = when (r.code) {
                            "payment_not_approved" -> CheckoutStage.NotCompleted(
                                orderId, r.detailString("approve_url") ?: approveUrl, "Payment not approved yet",
                                "Please approve the payment in PayPal to complete your order.", canCheckAgain = true,
                            )
                            "payment_declined" -> CheckoutStage.NotCompleted(
                                orderId, r.detailString("approve_url") ?: approveUrl, "Payment declined", r.message,
                            )
                            "payment_pending" -> CheckoutStage.NotCompleted(
                                orderId, approveUrl, "Payment processing", r.message, canCheckAgain = true,
                            )
                            "payment_not_found" -> {
                                payments.clearPendingOrder()
                                CheckoutStage.Failed("We couldn't find this payment. Please start checkout again.")
                            }
                            else -> CheckoutStage.Failed(r.message, retryCaptureOf = orderId)
                        },
                    )
                }
            }
        }
    }

    private suspend fun complete(orderId: String) {
        _effects.send(CheckoutEffect.Completed(orderId))
    }

    // ---------- user actions on non-review stages ----------

    fun reopenPayPal() {
        val stage = _state.value.stage
        val url = approveUrlOf(stage) ?: return
        val orderId = currentOrderId() ?: return
        _state.update { it.copy(stage = CheckoutStage.AwaitingApproval(orderId, url)) }
        viewModelScope.launch { _effects.send(CheckoutEffect.OpenUrl(url)) }
    }

    /** Give up on the current PayPal order (nothing was charged) and return to the review step. */
    fun startOver() {
        payments.clearPendingOrder()
        _state.update { it.copy(stage = CheckoutStage.Review, approved = false) }
        viewModelScope.launch { carts.refresh() }
    }

    fun browserUnavailable() = _state.update { it.copy(notice = "No browser available to open PayPal.") }

    fun noticeShown() = _state.update { it.copy(notice = null) }

    private fun currentOrderId(): String? = when (val s = _state.value.stage) {
        is CheckoutStage.AwaitingApproval -> s.orderId
        is CheckoutStage.Capturing -> s.orderId
        is CheckoutStage.NotCompleted -> s.orderId
        is CheckoutStage.Failed -> s.retryCaptureOf
        else -> null
    }

    private fun approveUrlOf(stage: CheckoutStage): String? = when (stage) {
        is CheckoutStage.AwaitingApproval -> stage.approveUrl
        is CheckoutStage.NotCompleted -> stage.approveUrl
        else -> null
    }

    fun approvalText(cart: Cart): String =
        "I approve paying ${formatUsd(cart.subtotal)} to CartPilot with PayPal (Sandbox, no real money)."

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { CheckoutViewModel(AppContainer.cartRepository, AppContainer.paymentRepository, AppContainer.events) }
        }
    }
}
