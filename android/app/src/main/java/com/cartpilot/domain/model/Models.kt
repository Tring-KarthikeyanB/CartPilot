package com.cartpilot.domain.model

enum class StockStatus(val label: String) {
    InStock("In stock"),
    LowStock("Only a few left"),
    OutOfStock("Out of stock");

    companion object {
        fun from(value: String): StockStatus = when (value) {
            "in_stock" -> InStock
            "low_stock" -> LowStock
            else -> OutOfStock
        }
    }
}

fun categoryLabel(id: String): String = when (id) {
    "keyboards" -> "Keyboards"
    "mice" -> "Mice"
    "headphones" -> "Headphones"
    "laptop_stands" -> "Laptop stands"
    "monitors" -> "Monitors"
    "webcams" -> "Webcams"
    "desk_accessories" -> "Desk accessories"
    else -> id.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

data class Product(
    val id: String,
    val name: String,
    val brand: String,
    val category: String,
    val description: String,
    val price: Double,
    val rating: Double,
    val reviewCount: Int,
    val imageUrl: String,
    val stock: StockStatus,
    val features: List<String>,
) {
    val available: Boolean get() = stock != StockStatus.OutOfStock
    val categoryLabel: String get() = categoryLabel(category)
}

data class Category(val id: String, val label: String, val productCount: Int)

data class CartItem(
    val productId: String,
    val name: String,
    val category: String,
    val imageUrl: String,
    val stock: StockStatus,
    val unitPrice: Double,
    val quantity: Int,
    val lineTotal: Double,
)

data class Cart(
    val items: List<CartItem>,
    val itemCount: Int,
    val subtotal: Double,
    val budget: Double?,
    val budgetRemaining: Double?,
    val overBudget: Boolean,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    companion object {
        val Empty = Cart(emptyList(), 0, 0.0, null, null, false)
    }
}

data class Saving(
    val replaceProductId: String,
    val replaceName: String,
    val withProductId: String,
    val withName: String,
    val withRating: Double,
    val savings: Double,
)

data class BudgetCheck(
    val budget: Double?,
    val subtotal: Double,
    val remaining: Double?,
    val withinBudget: Boolean,
    val overage: Double,
    val suggestions: List<Saving>,
    val message: String,
)

data class ComparedProduct(val product: Product, val valueScore: Double, val uniqueFeatures: List<String>)

data class Comparison(
    val products: List<ComparedProduct>,
    val sharedFeatures: List<String>,
    val cheapestId: String,
    val highestRatedId: String,
    val bestValueId: String,
    val summary: String,
)

data class AgentAction(val tool: String, val ok: Boolean, val summary: String)

data class ChatTurn(
    val reply: String,
    val products: List<Product>,
    val actions: List<AgentAction>,
    val cart: Cart,
    val cartUpdated: Boolean,
    val budget: BudgetCheck?,
)

data class OrderItem(val productId: String, val name: String, val quantity: Int, val unitPrice: Double, val lineTotal: Double)

data class PendingOrder(val orderId: String, val approveUrl: String, val amount: Double, val items: List<OrderItem>)

data class Receipt(
    val orderId: String,
    val captureId: String,
    val paypalStatus: String,
    val amount: Double,
    val currency: String,
    val items: List<OrderItem>,
    val payerEmail: String?,
    val payerName: String?,
    val capturedAt: String,
)

data class OrderStatus(
    val orderId: String,
    val status: String, // awaiting_approval | cancelled | completed | failed
    val paypalStatus: String?,
    val canCapture: Boolean,
    val approveUrl: String?,
    val amount: Double,
    val receipt: Receipt?,
) {
    val isCompleted: Boolean get() = status == "completed" && receipt != null
}
