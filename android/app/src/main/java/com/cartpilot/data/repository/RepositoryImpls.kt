package com.cartpilot.data.repository

import com.cartpilot.core.AppResult
import com.cartpilot.core.SessionStore
import com.cartpilot.core.onSuccess
import com.cartpilot.core.safeApiCall
import com.cartpilot.data.model.AddItemRequest
import com.cartpilot.data.model.CaptureOrderRequestDto
import com.cartpilot.data.model.ChatRequestDto
import com.cartpilot.data.model.CreateOrderRequestDto
import com.cartpilot.data.model.ReplaceItemRequest
import com.cartpilot.data.model.SetBudgetRequest
import com.cartpilot.data.model.UpdateQuantityRequest
import com.cartpilot.data.remote.CartPilotApi
import com.cartpilot.domain.model.BudgetCheck
import com.cartpilot.domain.model.Cart
import com.cartpilot.domain.model.Category
import com.cartpilot.domain.model.ChatTurn
import com.cartpilot.domain.model.Comparison
import com.cartpilot.domain.model.OrderStatus
import com.cartpilot.domain.model.PendingOrder
import com.cartpilot.domain.model.Product
import com.cartpilot.domain.model.Receipt
import com.cartpilot.domain.repository.AgentRepository
import com.cartpilot.domain.repository.CartRepository
import com.cartpilot.domain.repository.PaymentRepository
import com.cartpilot.domain.repository.ProductRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProductRepositoryImpl(private val api: CartPilotApi) : ProductRepository {
    override suspend fun search(query: String?, category: String?): AppResult<List<Product>> = safeApiCall {
        api.products(query = query?.takeIf { it.isNotBlank() }, category = category).items.map { it.toDomain() }
    }

    override suspend fun categories(): AppResult<List<Category>> = safeApiCall { api.categories().map { it.toDomain() } }

    override suspend fun compare(productIds: List<String>): AppResult<Comparison> =
        safeApiCall { api.compare(productIds.joinToString(",")).toDomain() }
}

class CartRepositoryImpl(private val api: CartPilotApi, private val session: SessionStore) : CartRepository {
    private val _cart = MutableStateFlow<Cart?>(null)
    override val cart: StateFlow<Cart?> = _cart.asStateFlow()

    private suspend fun update(block: suspend (String) -> com.cartpilot.data.model.CartDto): AppResult<Cart> =
        safeApiCall { block(session.sessionId).toDomain() }.onSuccess { _cart.value = it }

    override suspend fun refresh() = update { api.cart(it) }
    override suspend fun add(productId: String, quantity: Int) = update { api.addItem(it, AddItemRequest(productId, quantity)) }
    override suspend fun setQuantity(productId: String, quantity: Int) =
        update { api.setQuantity(it, productId, UpdateQuantityRequest(quantity)) }
    override suspend fun remove(productId: String) = update { api.removeItem(it, productId) }
    override suspend fun replace(oldProductId: String, newProductId: String) =
        update { api.replaceItem(it, ReplaceItemRequest(oldProductId, newProductId)) }
    override suspend fun setBudget(amount: Double?) = update { api.setBudget(it, SetBudgetRequest(amount)) }

    override suspend fun budgetCheck(): AppResult<BudgetCheck> = safeApiCall { api.budgetCheck(session.sessionId).toDomain() }

    override fun publish(cart: Cart) {
        _cart.value = cart
    }
}

class AgentRepositoryImpl(
    private val api: CartPilotApi,
    private val session: SessionStore,
    private val carts: CartRepository,
) : AgentRepository {
    override suspend fun chat(message: String): AppResult<ChatTurn> =
        safeApiCall { api.chat(ChatRequestDto(session.sessionId, message)).toDomain() }
            .onSuccess { carts.publish(it.cart) }

    override suspend fun resetConversation(): AppResult<Unit> = safeApiCall {
        val response = api.resetConversation(session.sessionId)
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
    }
}

class PaymentRepositoryImpl(
    private val api: CartPilotApi,
    private val session: SessionStore,
    private val carts: CartRepository,
) : PaymentRepository {
    private val _lastReceipt = MutableStateFlow<Receipt?>(null)
    override val lastReceipt: StateFlow<Receipt?> = _lastReceipt.asStateFlow()

    override val pendingOrderId: String? get() = session.pendingOrderId

    override suspend fun createOrder(expectedTotal: Double): AppResult<PendingOrder> =
        safeApiCall { api.createOrder(CreateOrderRequestDto(session.sessionId, expectedTotal, userConfirmed = true)).toDomain() }
            .onSuccess { session.pendingOrderId = it.orderId }

    override suspend fun capture(orderId: String): AppResult<Receipt> =
        safeApiCall { api.captureOrder(CaptureOrderRequestDto(session.sessionId, orderId)).toDomain() }
            .onSuccess {
                _lastReceipt.value = it
                session.pendingOrderId = null
                carts.refresh() // backend removed the purchased items
            }

    override suspend fun status(orderId: String): AppResult<OrderStatus> =
        safeApiCall { api.orderStatus(orderId, session.sessionId).toDomain() }
            .onSuccess { if (it.isCompleted) _lastReceipt.value = it.receipt }

    override fun clearPendingOrder() {
        session.pendingOrderId = null
    }
}
