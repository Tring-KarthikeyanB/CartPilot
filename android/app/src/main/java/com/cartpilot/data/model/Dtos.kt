package com.cartpilot.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Wire models mirroring the FastAPI schemas (backend/app/schemas). snake_case on the wire.

@Serializable
data class ErrorEnvelopeDto(val error: ErrorBodyDto)

@Serializable
data class ErrorBodyDto(val code: String, val message: String, val details: kotlinx.serialization.json.JsonElement? = null) {
    val detailsObject: JsonObject? get() = details as? JsonObject
}

// ---------- products ----------

@Serializable
data class ProductDto(
    val id: String,
    val name: String,
    val brand: String,
    val category: String,
    val description: String,
    val price: Double,
    val currency: String,
    val rating: Double,
    @SerialName("review_count") val reviewCount: Int,
    @SerialName("image_url") val imageUrl: String,
    @SerialName("stock_status") val stockStatus: String,
    val features: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

@Serializable
data class ProductListDto(val items: List<ProductDto>, val total: Int)

@Serializable
data class CategoryDto(val id: String, val label: String, @SerialName("product_count") val productCount: Int)

@Serializable
data class ComparedProductDto(
    val product: ProductDto,
    @SerialName("value_score") val valueScore: Double,
    @SerialName("unique_features") val uniqueFeatures: List<String>,
)

@Serializable
data class ComparisonDto(
    val products: List<ComparedProductDto>,
    @SerialName("shared_features") val sharedFeatures: List<String>,
    @SerialName("cheapest_id") val cheapestId: String,
    @SerialName("highest_rated_id") val highestRatedId: String,
    @SerialName("best_value_id") val bestValueId: String,
    val summary: String,
)

// ---------- cart ----------

@Serializable
data class CartItemDto(
    @SerialName("product_id") val productId: String,
    val name: String,
    val category: String,
    @SerialName("image_url") val imageUrl: String,
    @SerialName("stock_status") val stockStatus: String,
    @SerialName("unit_price") val unitPrice: Double,
    val quantity: Int,
    @SerialName("line_total") val lineTotal: Double,
)

@Serializable
data class CartDto(
    @SerialName("session_id") val sessionId: String,
    val items: List<CartItemDto>,
    @SerialName("item_count") val itemCount: Int,
    val subtotal: Double,
    val currency: String,
    val budget: Double? = null,
    @SerialName("budget_remaining") val budgetRemaining: Double? = null,
    @SerialName("over_budget") val overBudget: Boolean,
)

@Serializable
data class SavingDto(
    @SerialName("replace_product_id") val replaceProductId: String,
    @SerialName("replace_name") val replaceName: String,
    @SerialName("with_product_id") val withProductId: String,
    @SerialName("with_name") val withName: String,
    @SerialName("with_rating") val withRating: Double,
    val savings: Double,
)

@Serializable
data class BudgetCheckDto(
    val budget: Double? = null,
    val subtotal: Double,
    val remaining: Double? = null,
    @SerialName("within_budget") val withinBudget: Boolean,
    val overage: Double,
    val suggestions: List<SavingDto>,
    val message: String,
)

@Serializable
data class AddItemRequest(@SerialName("product_id") val productId: String, val quantity: Int = 1)

@Serializable
data class UpdateQuantityRequest(val quantity: Int)

@Serializable
data class ReplaceItemRequest(
    @SerialName("old_product_id") val oldProductId: String,
    @SerialName("new_product_id") val newProductId: String,
)

@Serializable
data class SetBudgetRequest(val amount: Double? = null)

// ---------- agent ----------

@Serializable
data class ChatRequestDto(@SerialName("session_id") val sessionId: String, val message: String)

@Serializable
data class AgentActionDto(val tool: String, val ok: Boolean, val summary: String)

@Serializable
data class ChatResponseDto(
    @SerialName("session_id") val sessionId: String,
    val reply: String,
    val products: List<ProductDto>,
    val actions: List<AgentActionDto>,
    val cart: CartDto,
    @SerialName("cart_updated") val cartUpdated: Boolean,
    val budget: BudgetCheckDto? = null,
)

// ---------- payments ----------

@Serializable
data class OrderItemDto(
    @SerialName("product_id") val productId: String,
    val name: String,
    val quantity: Int,
    @SerialName("unit_price") val unitPrice: Double,
    @SerialName("line_total") val lineTotal: Double,
)

@Serializable
data class CreateOrderRequestDto(
    @SerialName("session_id") val sessionId: String,
    @SerialName("expected_total") val expectedTotal: Double,
    @SerialName("user_confirmed") val userConfirmed: Boolean,
)

@Serializable
data class CreateOrderResponseDto(
    @SerialName("order_id") val orderId: String,
    @SerialName("approve_url") val approveUrl: String,
    val status: String,
    val amount: Double,
    val currency: String,
    val items: List<OrderItemDto>,
)

@Serializable
data class CaptureOrderRequestDto(@SerialName("session_id") val sessionId: String, @SerialName("order_id") val orderId: String)

@Serializable
data class ReceiptDto(
    @SerialName("order_id") val orderId: String,
    @SerialName("capture_id") val captureId: String,
    val status: String,
    @SerialName("paypal_status") val paypalStatus: String,
    val amount: Double,
    val currency: String,
    val items: List<OrderItemDto>,
    @SerialName("payer_email") val payerEmail: String? = null,
    @SerialName("payer_name") val payerName: String? = null,
    @SerialName("captured_at") val capturedAt: String,
)

@Serializable
data class OrderStatusDto(
    @SerialName("order_id") val orderId: String,
    val status: String,
    @SerialName("paypal_status") val paypalStatus: String? = null,
    @SerialName("can_capture") val canCapture: Boolean,
    @SerialName("approve_url") val approveUrl: String? = null,
    val amount: Double,
    val currency: String,
    val receipt: ReceiptDto? = null,
)
