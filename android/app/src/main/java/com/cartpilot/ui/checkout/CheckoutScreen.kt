@file:OptIn(ExperimentalMaterial3Api::class)

package com.cartpilot.ui.checkout

import android.content.ActivityNotFoundException
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.core.net.toUri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CancelPresentation
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cartpilot.core.formatUsd
import com.cartpilot.domain.model.Cart
import com.cartpilot.ui.components.BackButton
import com.cartpilot.ui.components.LoadingState
import com.cartpilot.ui.components.MessageState
import com.cartpilot.ui.theme.CartPilotColors

@Composable
fun CheckoutRoute(
    onBack: () -> Unit,
    onSuccess: (orderId: String) -> Unit,
    viewModel: CheckoutViewModel = viewModel(factory = CheckoutViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CheckoutEffect.OpenUrl -> try {
                    CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .setDefaultColorSchemeParams(
                            CustomTabColorSchemeParams.Builder().setToolbarColor(CartPilotColors.Primary.toArgb()).build(),
                        )
                        .build()
                        .launchUrl(context, effect.url.toUri())
                } catch (_: ActivityNotFoundException) {
                    viewModel.browserUnavailable()
                }
                is CheckoutEffect.Completed -> onSuccess(effect.orderId)
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed() }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.noticeShown()
        }
    }

    val stage = state.stage
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Checkout") },
                navigationIcon = { if (stage is CheckoutStage.Review || stage is CheckoutStage.Failed) BackButton(onBack) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            val cart = state.cart
            if (stage is CheckoutStage.Review && cart != null && !cart.isEmpty) {
                PayBar(cart, state.approved, state.canPay, onApprovedChange = viewModel::setApproved, approvalText = viewModel.approvalText(cart), onPay = viewModel::pay)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (stage) {
                CheckoutStage.Review -> {
                    val cart = state.cart
                    when {
                        cart == null && state.loadingCart -> LoadingState("Loading your order…")
                        cart == null || cart.isEmpty -> MessageState(
                            icon = Icons.Outlined.ShoppingCart, title = "Nothing to check out",
                            message = "Your cart is empty.", primaryAction = "Back" to onBack,
                        )
                        else -> OrderSummary(cart)
                    }
                }
                CheckoutStage.CreatingOrder -> LoadingState("Creating your secure PayPal order…")
                is CheckoutStage.AwaitingApproval -> if (stage.checking) {
                    LoadingState("Checking your payment with PayPal…")
                } else {
                    MessageState(
                        icon = Icons.Outlined.OpenInBrowser,
                        title = "Complete your payment in PayPal",
                        message = "Approve the payment in the PayPal window. You'll come back here automatically.",
                        primaryAction = "Open PayPal" to viewModel::reopenPayPal,
                        secondaryAction = "I've completed payment" to viewModel::checkAgain,
                    )
                }
                is CheckoutStage.Capturing -> LoadingState("Confirming your payment with PayPal…\nPlease don't close the app.")
                is CheckoutStage.NotCompleted -> NotCompletedState(stage, viewModel)
                is CheckoutStage.Failed -> MessageState(
                    icon = Icons.Outlined.ErrorOutline,
                    title = "Payment problem",
                    message = stage.message,
                    iconTint = MaterialTheme.colorScheme.error,
                    primaryAction = stage.retryCaptureOf?.let { id -> "Try again" to { viewModel.retryCapture(id) } }
                        ?: ("Back to review" to viewModel::startOver),
                    secondaryAction = if (stage.retryCaptureOf != null) "Start over" to viewModel::startOver else null,
                )
            }
        }
    }
}

@Composable
private fun NotCompletedState(stage: CheckoutStage.NotCompleted, viewModel: CheckoutViewModel) {
    Column(Modifier.fillMaxSize()) {
        MessageState(
            icon = Icons.Outlined.CancelPresentation,
            title = stage.title,
            message = stage.message,
            iconTint = CartPilotColors.Accent,
            primaryAction = if (stage.approveUrl != null) "Continue in PayPal" to viewModel::reopenPayPal else null,
            secondaryAction = "Back to cart review" to viewModel::startOver,
            modifier = Modifier.weight(1f),
        )
        if (stage.canCheckAgain) {
            TextButton(onClick = viewModel::checkAgain, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 24.dp)) {
                Text("Check payment status again")
            }
        }
    }
}

@Composable
private fun OrderSummary(cart: Cart) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Order summary", style = MaterialTheme.typography.titleMedium)
                cart.items.forEach { item ->
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${item.quantity} × ${formatUsd(item.unitPrice)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(formatUsd(item.lineTotal), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                SummaryLine("Subtotal", formatUsd(cart.subtotal))
                SummaryLine("Shipping", "Free")
                SummaryLine("Total", formatUsd(cart.subtotal), bold = true)
                cart.budget?.let { budget ->
                    Text(
                        if (cart.overBudget) "This is ${formatUsd(-(cart.budgetRemaining ?: 0.0))} over your ${formatUsd(budget)} budget."
                        else "Within your ${formatUsd(budget)} budget (${formatUsd(cart.budgetRemaining ?: 0.0)} left).",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (cart.overBudget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Prices and the total are calculated by CartPilot's server. You'll approve the payment on PayPal's secure page. We never see your PayPal login.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String, bold: Boolean = false) {
    Row {
        Text(label, style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = if (bold) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else null)
    }
}

@Composable
private fun PayBar(
    cart: Cart,
    approved: Boolean,
    canPay: Boolean,
    approvalText: String,
    onApprovedChange: (Boolean) -> Unit,
    onPay: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onApprovedChange(!approved) },
            ) {
                Checkbox(checked = approved, onCheckedChange = onApprovedChange)
                Text(approvalText, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onPay,
                enabled = canPay,
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pay ${formatUsd(cart.subtotal)} with PayPal", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
