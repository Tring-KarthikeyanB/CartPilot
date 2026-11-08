@file:OptIn(ExperimentalMaterial3Api::class)

package com.cartpilot.ui.cart

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cartpilot.core.formatUsd
import com.cartpilot.domain.model.BudgetCheck
import com.cartpilot.domain.model.Cart
import com.cartpilot.domain.model.CartItem
import com.cartpilot.domain.model.Saving
import com.cartpilot.domain.model.StockStatus
import com.cartpilot.ui.components.BackButton
import com.cartpilot.ui.components.ErrorState
import com.cartpilot.ui.components.LoadingState
import com.cartpilot.ui.components.MessageState
import com.cartpilot.ui.components.ProductImage
import com.cartpilot.ui.components.StockBadge
import com.cartpilot.ui.theme.CartPilotColors

@Composable
fun CartRoute(
    onBack: () -> Unit,
    onBrowse: () -> Unit,
    onCheckout: () -> Unit,
    onAskAgent: () -> Unit,
    viewModel: CartViewModel = viewModel(factory = CartViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.noticeShown()
        }
    }
    CartScreen(state, viewModel, onBack, onBrowse, onCheckout, onAskAgent, snackbar)
}

@Composable
fun CartScreen(
    state: CartUiState,
    actions: CartActions,
    onBack: () -> Unit,
    onBrowse: () -> Unit,
    onCheckout: () -> Unit,
    onAskAgent: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val viewModel = actions
    val cart = state.cart

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (cart != null && !cart.isEmpty) "Your cart (${cart.itemCount})" else "Your cart") },
                navigationIcon = { BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (cart != null && !cart.isEmpty) CheckoutBar(cart, enabled = state.busyIds.isEmpty(), onCheckout = onCheckout)
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingState("Loading your cart…")
                state.error != null && cart == null -> ErrorState(state.error!!, onRetry = viewModel::refresh)
                cart == null || cart.isEmpty -> MessageState(
                    icon = Icons.Outlined.ShoppingCart,
                    title = "Your cart is empty",
                    message = "Ask CartPilot to build a setup for your budget, or browse the catalogue.",
                    primaryAction = "Chat with CartPilot" to onBack,
                    secondaryAction = "Browse products" to onBrowse,
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "budget") {
                        BudgetCard(cart, state.budgetCheck, onEditBudget = { viewModel.editBudget(true) })
                    }
                    items(cart.items, key = { it.productId }) { item ->
                        CartItemRow(
                            item = item,
                            busy = item.productId in state.busyIds,
                            saving = state.budgetCheck?.suggestions?.firstOrNull { it.replaceProductId == item.productId },
                            onIncrement = { viewModel.increment(item.productId, item.quantity) },
                            onDecrement = { viewModel.decrement(item.productId, item.quantity) },
                            onRemove = { viewModel.remove(item.productId, item.name) },
                            onApplySaving = viewModel::applySaving,
                        )
                    }
                    item(key = "ai") {
                        OutlinedButton(
                            onClick = {
                                viewModel.askAgentToOptimize()
                                onAskAgent()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Outlined.AutoAwesome, null, tint = CartPilotColors.Accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Ask CartPilot to optimize my cart")
                        }
                    }
                }
            }
        }
    }

    if (state.editingBudget) {
        BudgetDialog(current = cart?.budget, onDismiss = { viewModel.editBudget(false) }, onSave = viewModel::setBudget)
    }
}

@Composable
private fun BudgetCard(cart: Cart, check: BudgetCheck?, onEditBudget: () -> Unit) {
    val budget = cart.budget
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (cart.overBudget) CartPilotColors.Accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (cart.overBudget) CartPilotColors.Accent else MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Budget", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onEditBudget) {
                    Icon(Icons.Outlined.Edit, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (budget == null) "Set budget" else formatUsd(budget))
                }
            }
            if (budget == null) {
                Text(
                    "Set a budget and CartPilot will keep your cart within it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(
                    progress = { (cart.subtotal / budget).toFloat().coerceIn(0f, 1f) },
                    color = if (cart.overBudget) CartPilotColors.Accent else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(MaterialTheme.shapes.small),
                )
                Row {
                    Text("${formatUsd(cart.subtotal)} of ${formatUsd(budget)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    val remaining = cart.budgetRemaining ?: 0.0
                    Text(
                        if (cart.overBudget) "${formatUsd(-remaining)} over" else "${formatUsd(remaining)} left",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (cart.overBudget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                if (cart.overBudget) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "You're over budget. Try the suggested swaps below.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            val totalSavings = check?.suggestions?.sumOf { it.savings } ?: 0.0
            if (totalSavings > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Savings, null, tint = CartPilotColors.Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Smart swaps could save up to ${formatUsd(totalSavings)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CartItemRow(
    item: CartItem,
    busy: Boolean,
    saving: Saving?,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onRemove: () -> Unit,
    onApplySaving: (Saving) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProductImage(item.imageUrl, item.name, Modifier.size(76.dp).clip(MaterialTheme.shapes.small))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${formatUsd(item.unitPrice)} each", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (item.stock != StockStatus.InStock) StockBadge(item.stock)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QuantityStepper(item.quantity, enabled = !busy, onIncrement = onIncrement, onDecrement = onDecrement)
                        Spacer(Modifier.weight(1f))
                        Text(formatUsd(item.lineTotal), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        IconButton(onClick = onRemove, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, "Remove ${item.name}") }
                    }
                }
            }
            if (saving != null) {
                Surface(shape = MaterialTheme.shapes.small, color = CartPilotColors.PrimaryContainer.copy(alpha = 0.55f)) {
                    Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Swap to ${saving.withName} (${saving.withRating}★) and save ${formatUsd(saving.savings)}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onApplySaving(saving) }, enabled = !busy) { Text("Swap") }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuantityStepper(quantity: Int, enabled: Boolean, onIncrement: () -> Unit, onDecrement: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
    ) {
        // Removing the line is the separate trash button; the stepper stops at 1.
        IconButton(onClick = onDecrement, enabled = enabled && quantity > 1, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Outlined.Remove, "Decrease", modifier = Modifier.size(16.dp))
        }
        Text("$quantity", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp))
        IconButton(onClick = onIncrement, enabled = enabled && quantity < 10, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Outlined.Add, "Increase", modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun CheckoutBar(cart: Cart, enabled: Boolean, onCheckout: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Row(
            Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatUsd(cart.subtotal), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Button(onClick = onCheckout, enabled = enabled, modifier = Modifier.height(52.dp)) { Text("Checkout") }
        }
    }
}

@Composable
private fun BudgetDialog(current: Double?, onDismiss: () -> Unit, onSave: (Double?) -> Unit) {
    var text by remember { mutableStateOf(current?.let { String.format(java.util.Locale.US, "%.0f", it) } ?: "") }
    val amount = text.toDoubleOrNull()
    val valid = amount != null && amount > 0 && amount <= 100_000
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shopping budget") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter { it.isDigit() || it == '.' }.take(9) },
                prefix = { Text("$") },
                singleLine = true,
                isError = text.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                label = { Text("Total budget (USD)") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(amount) }, enabled = valid) { Text("Save") } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSave(null) }) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
