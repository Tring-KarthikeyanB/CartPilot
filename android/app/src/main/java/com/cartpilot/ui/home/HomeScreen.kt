@file:OptIn(ExperimentalMaterial3Api::class)

package com.cartpilot.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cartpilot.core.formatUsd
import com.cartpilot.domain.model.Cart
import com.cartpilot.domain.model.Product
import com.cartpilot.ui.components.CartIconButton
import com.cartpilot.ui.components.ProductCard
import com.cartpilot.ui.components.ProductDetailSheet
import com.cartpilot.ui.theme.CartPilotColors
import com.cartpilot.ui.theme.CartPilotTheme

@Composable
fun HomeRoute(
    onOpenCart: () -> Unit,
    onBrowse: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.noticeShown()
        }
    }

    HomeScreen(
        state = state,
        snackbar = snackbar,
        onInputChange = viewModel::onInputChange,
        onSend = { viewModel.send() },
        onPrompt = viewModel::send,
        onRetry = viewModel::retry,
        onRetryConnection = viewModel::checkConnection,
        onNewConversation = viewModel::newConversation,
        onProductClick = viewModel::selectProduct,
        onOpenCart = onOpenCart,
        onBrowse = onBrowse,
    )
    state.selectedProduct?.let { product ->
        ProductDetailSheet(
            product = product,
            onDismiss = { viewModel.selectProduct(null) },
            adding = state.addingToCart,
            onAdd = viewModel::addSelectedToCart,
        )
    }
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onPrompt: (String) -> Unit,
    onRetry: (ChatMessage.Error) -> Unit,
    onRetryConnection: () -> Unit,
    onNewConversation: () -> Unit,
    onProductClick: (Product) -> Unit,
    onOpenCart: () -> Unit,
    onBrowse: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size, state.isSending) {
        val count = state.messages.size + if (state.isSending) 1 else 0
        if (count > 0) listState.animateScrollToItem(count) // index past the header item
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(34.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("CartPilot", style = MaterialTheme.typography.titleMedium)
                            Text(
                                connectionLabel(state.connection),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (state.connection is ConnectionState.Offline) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (state.messages.isNotEmpty()) {
                        IconButton(onClick = onNewConversation) { Icon(Icons.Outlined.RestartAlt, "New conversation") }
                    }
                    IconButton(onClick = onBrowse) { Icon(Icons.Outlined.Storefront, "Browse products") }
                    CartIconButton(state.cartCount, onOpenCart)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            ChatInput(
                value = state.input,
                enabled = !state.isSending,
                canSend = state.canSend,
                onValueChange = onInputChange,
                onSend = onSend,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                if (state.messages.isEmpty()) {
                    Welcome(state.connection, onPrompt = onPrompt, onRetryConnection = onRetryConnection, enabled = !state.isSending)
                }
            }
            items(state.messages, key = { it.id }) { message ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + slideInVertically { it / 4 },
                ) {
                    when (message) {
                        is ChatMessage.User -> UserBubble(message.text)
                        is ChatMessage.Assistant -> AssistantMessage(message, onProductClick, onOpenCart)
                        is ChatMessage.Error -> ErrorBubble(message, onRetry = { onRetry(message) })
                    }
                }
            }
            if (state.isSending) item(key = "typing") { TypingIndicator() }
        }
    }
}

private fun connectionLabel(connection: ConnectionState): String = when (connection) {
    ConnectionState.Checking -> "Connecting…"
    is ConnectionState.Connected -> if (connection.status.aiReady) "AI shopping agent · online" else "Online · AI not configured"
    is ConnectionState.Offline -> "Offline"
}

@Composable
private fun Welcome(connection: ConnectionState, onPrompt: (String) -> Unit, onRetryConnection: () -> Unit, enabled: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Hi! I'm your AI shopping agent.", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Tell me what you need and your budget. I'll search the catalogue, compare options, and build a cart that fits. You approve the payment.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (connection is ConnectionState.Offline) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(connection.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRetryConnection) { Text("Retry") }
                }
            }
        }
        Text("Try asking", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SuggestedPrompts.forEach { prompt ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                    .clickable(enabled = enabled) { onPrompt(prompt) },
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = CartPilotColors.Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(prompt, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun AssistantMessage(message: ChatMessage.Assistant, onProductClick: (Product) -> Unit, onOpenCart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            message.text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .widthIn(max = 340.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        if (message.actions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                message.actions.forEach { action ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(action.summary, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (message.products.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(message.products, key = { it.id }) { product ->
                    ProductCard(product, onClick = { onProductClick(product) }, onAdd = null, modifier = Modifier.width(180.dp))
                }
            }
        }
        message.cart?.let { CartPill(it, onOpenCart) }
    }
}

@Composable
private fun CartPill(cart: Cart, onOpenCart: () -> Unit) {
    val budgetText = when {
        cart.budget == null -> ""
        cart.overBudget -> " · ${formatUsd(-(cart.budgetRemaining ?: 0.0))} over budget"
        else -> " · ${formatUsd(cart.budgetRemaining ?: 0.0)} left"
    }
    Surface(
        onClick = onOpenCart,
        shape = RoundedCornerShape(50),
        color = if (cart.overBudget) CartPilotColors.Accent.copy(alpha = 0.18f) else CartPilotColors.PrimaryContainer,
    ) {
        Text(
            "Cart: ${cart.itemCount} items · ${formatUsd(cart.subtotal)}$budgetText  ›  View cart",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ErrorBubble(message: ChatMessage.Error, onRetry: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.error.copy(alpha = 0.07f)) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(message.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        Modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(500, delayMillis = i * 150), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(8.dp).alpha(alpha).background(MaterialTheme.colorScheme.primary, CircleShape))
        }
        Spacer(Modifier.width(4.dp))
        Text("CartPilot is shopping…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChatInput(value: String, enabled: Boolean, canSend: Boolean, onValueChange: (String) -> Unit, onSend: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                placeholder = { Text("What are you shopping for?") },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(52.dp)
                    .background(
                        if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    )
                    .clickable(enabled = canSend, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White) }
        }
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun HomePreview() {
    CartPilotTheme {
        HomeScreen(
            state = HomeUiState(
                messages = listOf(
                    ChatMessage.User(0, "Find headphones below \$100"),
                    ChatMessage.Assistant(1, "Here are two great options:\n• Studio Monitor Wired Headphones (\$39.99)"),
                ),
                isSending = true,
            ),
            onInputChange = {}, onSend = {}, onPrompt = {}, onRetry = {}, onRetryConnection = {}, onNewConversation = {},
            onProductClick = {}, onOpenCart = {}, onBrowse = {},
        )
    }
}
