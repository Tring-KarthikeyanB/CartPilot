@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.cartpilot.ui.products

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cartpilot.core.formatUsd
import com.cartpilot.domain.model.Comparison
import com.cartpilot.ui.components.BackButton
import com.cartpilot.ui.components.CartIconButton
import com.cartpilot.ui.components.ErrorState
import com.cartpilot.ui.components.FeatureChip
import com.cartpilot.ui.components.LoadingState
import com.cartpilot.ui.components.MessageState
import com.cartpilot.ui.components.ProductCard
import com.cartpilot.ui.components.ProductDetailSheet
import com.cartpilot.ui.components.ProductImage
import com.cartpilot.ui.components.RatingRow
import com.cartpilot.ui.components.StockBadge
import com.cartpilot.ui.theme.CartPilotColors

@Composable
fun ProductsRoute(
    initialCategory: String?,
    onBack: () -> Unit,
    onOpenCart: () -> Unit,
    viewModel: ProductsViewModel = viewModel(factory = ProductsViewModel.factory(initialCategory)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            viewModel.noticeShown()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Discover") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    TextButton(onClick = viewModel::toggleCompareMode) { Text(if (state.compareMode) "Done" else "Compare") }
                    CartIconButton(state.cartCount, onOpenCart)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (state.compareMode) {
                CompareBar(count = state.compareIds.size, comparing = state.comparing, onCompare = viewModel::compare)
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text("Search keyboards, ANC headphones, 4K…") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) IconButton(onClick = { viewModel.onQueryChange("") }) { Icon(Icons.Outlined.Close, "Clear") }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.categories, key = { it.id }) { c ->
                    FilterChip(
                        selected = state.category == c.id,
                        onClick = { viewModel.onCategory(c.id) },
                        label = { Text(c.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = Color.White,
                        ),
                    )
                }
            }
            if (state.compareMode) {
                Text(
                    "Select 2–$MAX_COMPARE products to compare",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when {
                state.loading && state.products.isEmpty() -> LoadingState("Loading products…")
                state.error != null -> ErrorState(state.error!!, onRetry = viewModel::retry)
                state.products.isEmpty() -> MessageState(
                    icon = Icons.Outlined.SearchOff,
                    title = "No matches",
                    message = "Try a different search or category, or ask CartPilot in the chat.",
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.products, key = { it.id }) { product ->
                        val selected = product.id in state.compareIds
                        ProductCard(
                            product = product,
                            selected = selected,
                            badge = if (selected) "Compare #${state.compareIds.indexOf(product.id) + 1}" else null,
                            adding = state.addingId == product.id,
                            onClick = {
                                if (state.compareMode) viewModel.toggleCompare(product) else viewModel.selectProduct(product)
                            },
                            onAdd = { viewModel.addToCart(product) },
                        )
                    }
                }
            }
        }
    }

    state.selectedProduct?.let { product ->
        ProductDetailSheet(
            product = product,
            onDismiss = { viewModel.selectProduct(null) },
            onAdd = { viewModel.addToCart(product) },
            adding = state.addingId == product.id,
        )
    }
    state.comparison?.let { comparison ->
        ComparisonSheet(comparison, onDismiss = viewModel::dismissComparison, onAdd = { id ->
            comparison.products.firstOrNull { it.product.id == id }?.let { viewModel.addToCart(it.product) }
        })
    }
}

@Composable
private fun CompareBar(count: Int, comparing: Boolean, onCompare: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Row(
            Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = onCompare, enabled = count >= 2 && !comparing) {
                if (comparing) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("Compare")
            }
        }
    }
}

@Composable
private fun ComparisonSheet(comparison: Comparison, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Comparison", style = MaterialTheme.typography.titleLarge)
            Surface(shape = MaterialTheme.shapes.medium, color = CartPilotColors.PrimaryContainer.copy(alpha = 0.5f)) {
                Text(comparison.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
            }
            comparison.products.forEach { c ->
                val badges = buildList {
                    if (c.product.id == comparison.cheapestId) add("Cheapest")
                    if (c.product.id == comparison.highestRatedId) add("Top rated")
                    if (c.product.id == comparison.bestValueId) add("Best value")
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProductImage(c.product.imageUrl, c.product.name, Modifier.size(72.dp).clip(MaterialTheme.shapes.small))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.product.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(formatUsd(c.product.price), fontWeight = FontWeight.Bold)
                            RatingRow(c.product.rating, reviewCount = c.product.reviewCount)
                        }
                        if (badges.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                badges.forEach { b ->
                                    Text(
                                        b,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(if (b == "Best value") CartPilotColors.Accent else MaterialTheme.colorScheme.primary)
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                        if (c.uniqueFeatures.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                c.uniqueFeatures.take(5).forEach { FeatureChip(it, highlighted = true) }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!c.product.available) StockBadge(c.product.stock)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { onAdd(c.product.id) }, enabled = c.product.available) { Text("Add to cart") }
                        }
                    }
                }
            }
            if (comparison.sharedFeatures.isNotEmpty()) {
                Text("All have", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    comparison.sharedFeatures.forEach { FeatureChip(it) }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
