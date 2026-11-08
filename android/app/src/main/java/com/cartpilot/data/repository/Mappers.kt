package com.cartpilot.data.repository

import com.cartpilot.data.model.AgentActionDto
import com.cartpilot.data.model.BudgetCheckDto
import com.cartpilot.data.model.CartDto
import com.cartpilot.data.model.CategoryDto
import com.cartpilot.data.model.ChatResponseDto
import com.cartpilot.data.model.ComparisonDto
import com.cartpilot.data.model.CreateOrderResponseDto
import com.cartpilot.data.model.OrderItemDto
import com.cartpilot.data.model.OrderStatusDto
import com.cartpilot.data.model.ProductDto
import com.cartpilot.data.model.ReceiptDto
import com.cartpilot.domain.model.AgentAction
import com.cartpilot.domain.model.BudgetCheck
import com.cartpilot.domain.model.Cart
import com.cartpilot.domain.model.CartItem
import com.cartpilot.domain.model.Category
import com.cartpilot.domain.model.ChatTurn
import com.cartpilot.domain.model.ComparedProduct
import com.cartpilot.domain.model.Comparison
import com.cartpilot.domain.model.OrderItem
import com.cartpilot.domain.model.OrderStatus
import com.cartpilot.domain.model.PendingOrder
import com.cartpilot.domain.model.Product
import com.cartpilot.domain.model.Receipt
import com.cartpilot.domain.model.Saving
import com.cartpilot.domain.model.StockStatus

fun ProductDto.toDomain() = Product(
    id = id, name = name, brand = brand, category = category, description = description, price = price,
    rating = rating, reviewCount = reviewCount, imageUrl = imageUrl, stock = StockStatus.from(stockStatus), features = features,
)

fun CategoryDto.toDomain() = Category(id, label, productCount)

fun CartDto.toDomain() = Cart(
    items = items.map {
        CartItem(it.productId, it.name, it.category, it.imageUrl, StockStatus.from(it.stockStatus), it.unitPrice, it.quantity, it.lineTotal)
    },
    itemCount = itemCount, subtotal = subtotal, budget = budget, budgetRemaining = budgetRemaining, overBudget = overBudget,
)

fun BudgetCheckDto.toDomain() = BudgetCheck(
    budget = budget, subtotal = subtotal, remaining = remaining, withinBudget = withinBudget, overage = overage,
    suggestions = suggestions.map { Saving(it.replaceProductId, it.replaceName, it.withProductId, it.withName, it.withRating, it.savings) },
    message = message,
)

fun ComparisonDto.toDomain() = Comparison(
    products = products.map { ComparedProduct(it.product.toDomain(), it.valueScore, it.uniqueFeatures) },
    sharedFeatures = sharedFeatures, cheapestId = cheapestId, highestRatedId = highestRatedId, bestValueId = bestValueId, summary = summary,
)

fun AgentActionDto.toDomain() = AgentAction(tool, ok, summary)

fun ChatResponseDto.toDomain() = ChatTurn(
    reply = reply, products = products.map { it.toDomain() }, actions = actions.map { it.toDomain() },
    cart = cart.toDomain(), cartUpdated = cartUpdated, budget = budget?.toDomain(),
)

fun OrderItemDto.toDomain() = OrderItem(productId, name, quantity, unitPrice, lineTotal)

fun CreateOrderResponseDto.toDomain() = PendingOrder(orderId, approveUrl, amount, items.map { it.toDomain() })

fun ReceiptDto.toDomain() = Receipt(
    orderId = orderId, captureId = captureId, paypalStatus = paypalStatus, amount = amount, currency = currency,
    items = items.map { it.toDomain() }, payerEmail = payerEmail, payerName = payerName, capturedAt = capturedAt,
)

fun OrderStatusDto.toDomain() = OrderStatus(orderId, status, paypalStatus, canCapture, approveUrl, amount, receipt?.toDomain())
