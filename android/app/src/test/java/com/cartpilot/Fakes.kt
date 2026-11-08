package com.cartpilot

import com.cartpilot.core.AppResult
import com.cartpilot.domain.model.BackendStatus
import com.cartpilot.domain.model.BudgetCheck
import com.cartpilot.domain.model.Cart
import com.cartpilot.domain.model.CartItem
import com.cartpilot.domain.model.ChatTurn
import com.cartpilot.domain.model.OrderItem
import com.cartpilot.domain.model.OrderStatus
import com.cartpilot.domain.model.PendingOrder
import com.cartpilot.domain.model.Receipt
import com.cartpilot.domain.model.StockStatus
import com.cartpilot.domain.repository.AgentRepository
import com.cartpilot.domain.repository.CartRepository
import com.cartpilot.domain.repository.PaymentRepository
import com.cartpilot.domain.repository.SystemRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

val sampleCart = Cart(
    items = listOf(CartItem("kb-005", "Keyline K84", "keyboards", "http://x/kb.jpg", StockStatus.InStock, 79.99, 1, 79.99)),
    itemCount = 1, subtotal = 79.99, budget = null, budgetRemaining = null, overBudget = false,
)

fun receipt(orderId: String) = Receipt(
    orderId, "CAP-$orderId", "COMPLETED", 79.99, "USD", listOf(OrderItem("kb-005", "Keyline K84", 1, 79.99, 79.99)), null, null, "now",
)

class FakeSystemRepository(var result: AppResult<BackendStatus> = AppResult.Success(BackendStatus("0.1.0", "test", true, true))) :
    SystemRepository {
    override suspend fun checkHealth() = result
}

class FakeCartRepository(initial: Cart? = sampleCart) : CartRepository {
    private val _cart = MutableStateFlow(initial)
    override val cart: StateFlow<Cart?> = _cart
    var serverCart: Cart = initial ?: Cart.Empty
    var refreshCount = 0

    override suspend fun refresh(): AppResult<Cart> {
        refreshCount++
        _cart.value = serverCart
        return AppResult.Success(serverCart)
    }
    override suspend fun add(productId: String, quantity: Int) = refresh()
    override suspend fun setQuantity(productId: String, quantity: Int) = refresh()
    override suspend fun remove(productId: String) = refresh()
    override suspend fun replace(oldProductId: String, newProductId: String) = refresh()
    override suspend fun setBudget(amount: Double?) = refresh()
    override suspend fun budgetCheck(): AppResult<BudgetCheck> =
        AppResult.Success(BudgetCheck(null, serverCart.subtotal, null, true, 0.0, emptyList(), "ok"))
    override fun publish(cart: Cart) {
        _cart.value = cart
    }
}

class FakeAgentRepository(private val carts: FakeCartRepository) : AgentRepository {
    val results = ArrayDeque<AppResult<ChatTurn>>()
    val sent = mutableListOf<String>()
    var resets = 0

    override suspend fun chat(message: String): AppResult<ChatTurn> {
        sent += message
        val r = results.removeFirst()
        if (r is AppResult.Success) carts.publish(r.data.cart)
        return r
    }

    override suspend fun resetConversation(): AppResult<Unit> {
        resets++
        return AppResult.Success(Unit)
    }
}

class FakePaymentRepository : PaymentRepository {
    override var pendingOrderId: String? = null
    private val _lastReceipt = MutableStateFlow<Receipt?>(null)
    override val lastReceipt: StateFlow<Receipt?> = _lastReceipt

    var createResult: AppResult<PendingOrder> = AppResult.Success(PendingOrder("ORDER1", "https://paypal/approve", 79.99, emptyList()))
    var captureResult: (String) -> AppResult<Receipt> = { AppResult.Success(receipt(it)) }
    var statusResult: (String) -> AppResult<OrderStatus> = {
        AppResult.Success(OrderStatus(it, "awaiting_approval", "PAYER_ACTION_REQUIRED", false, "https://paypal/approve", 79.99, null))
    }
    val createdWithTotals = mutableListOf<Double>()
    val captured = mutableListOf<String>()
    var statusChecks = 0

    override suspend fun createOrder(expectedTotal: Double): AppResult<PendingOrder> {
        createdWithTotals += expectedTotal
        return createResult.also { if (it is AppResult.Success) pendingOrderId = it.data.orderId }
    }

    override suspend fun capture(orderId: String): AppResult<Receipt> {
        captured += orderId
        return captureResult(orderId).also {
            if (it is AppResult.Success) {
                _lastReceipt.value = it.data
                pendingOrderId = null
            }
        }
    }

    override suspend fun status(orderId: String): AppResult<OrderStatus> {
        statusChecks++
        return statusResult(orderId)
    }

    override fun clearPendingOrder() {
        pendingOrderId = null
    }
}
