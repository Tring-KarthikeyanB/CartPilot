package com.cartpilot.domain.repository

import com.cartpilot.core.AppResult
import com.cartpilot.domain.model.BudgetCheck
import com.cartpilot.domain.model.Cart
import com.cartpilot.domain.model.Category
import com.cartpilot.domain.model.ChatTurn
import com.cartpilot.domain.model.Comparison
import com.cartpilot.domain.model.OrderStatus
import com.cartpilot.domain.model.PendingOrder
import com.cartpilot.domain.model.Product
import com.cartpilot.domain.model.Receipt
import kotlinx.coroutines.flow.StateFlow

interface ProductRepository {
    suspend fun search(query: String?, category: String?): AppResult<List<Product>>
    suspend fun categories(): AppResult<List<Category>>
    suspend fun compare(productIds: List<String>): AppResult<Comparison>
}

/** Single source of truth for the session cart; every screen observes [cart]. */
interface CartRepository {
    val cart: StateFlow<Cart?>
    suspend fun refresh(): AppResult<Cart>
    suspend fun add(productId: String, quantity: Int = 1): AppResult<Cart>
    suspend fun setQuantity(productId: String, quantity: Int): AppResult<Cart>
    suspend fun remove(productId: String): AppResult<Cart>
    suspend fun replace(oldProductId: String, newProductId: String): AppResult<Cart>
    suspend fun setBudget(amount: Double?): AppResult<Cart>
    suspend fun budgetCheck(): AppResult<BudgetCheck>
    /** Publish a cart received from another endpoint (e.g. the agent's chat response). */
    fun publish(cart: Cart)
}

interface AgentRepository {
    suspend fun chat(message: String): AppResult<ChatTurn>
    suspend fun resetConversation(): AppResult<Unit>
}

interface PaymentRepository {
    /** Order awaiting PayPal approval, persisted so checkout survives the app being killed. */
    val pendingOrderId: String?
    val lastReceipt: StateFlow<Receipt?>
    suspend fun createOrder(expectedTotal: Double): AppResult<PendingOrder>
    suspend fun capture(orderId: String): AppResult<Receipt>
    suspend fun status(orderId: String): AppResult<OrderStatus>
    fun clearPendingOrder()
}
