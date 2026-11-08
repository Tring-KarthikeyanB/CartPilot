package com.cartpilot.ui.success

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cartpilot.core.formatUsd
import com.cartpilot.domain.model.Receipt
import com.cartpilot.ui.components.LoadingState
import com.cartpilot.ui.components.MessageState

@Composable
fun SuccessRoute(
    orderId: String,
    onDone: () -> Unit,
    viewModel: SuccessViewModel = viewModel(factory = SuccessViewModel.factory(orderId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SuccessScreen(state, onRetry = viewModel::load, onDone = onDone)
}

@Composable
fun SuccessScreen(state: SuccessUiState, onRetry: () -> Unit, onDone: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (val s = state) {
            SuccessUiState.Loading -> LoadingState("Loading your receipt…")
            is SuccessUiState.Error -> MessageState(
                icon = Icons.Outlined.ErrorOutline,
                title = "Receipt unavailable",
                message = s.message,
                iconTint = MaterialTheme.colorScheme.error,
                primaryAction = "Try again" to onRetry,
                secondaryAction = "Back to home" to onDone,
            )
            is SuccessUiState.Loaded -> ReceiptContent(s.receipt, onDone)
        }
    }
}

@Composable
private fun ReceiptContent(receipt: Receipt, onDone: () -> Unit) {
    val scale = remember { Animatable(0.4f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.size(88.dp).scale(scale.value).background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
            Text("Payment successful", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(
                "PayPal confirmed your payment of ${formatUsd(receipt.amount)}" +
                    (receipt.payerName?.let { ", $it" } ?: "") + ".",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DetailLine("Status", "Paid · ${receipt.paypalStatus}")
                    DetailLine("Order ID", receipt.orderId)
                    DetailLine("PayPal capture", receipt.captureId)
                    receipt.payerEmail?.let { DetailLine("Paid by", it) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Text("Items", style = MaterialTheme.typography.titleMedium)
                    receipt.items.forEach { item ->
                        Row {
                            Text("${item.quantity} × ${item.name}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(formatUsd(item.lineTotal), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Row {
                        Text("Total paid", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(formatUsd(receipt.amount) + " " + receipt.currency, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(
                "PayPal Sandbox transaction. No real money was moved.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(16.dp).height(54.dp)) { Text("Back to home") }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.6f), textAlign = TextAlign.End)
    }
}
