package com.cartpilot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AddShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cartpilot.core.formatCount
import com.cartpilot.core.formatUsd
import com.cartpilot.domain.model.Product
import com.cartpilot.domain.model.StockStatus
import com.cartpilot.ui.theme.CartPilotColors

@Composable
fun ProductImage(url: String, contentDescription: String?, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.background(CartPilotColors.PrimaryContainer.copy(alpha = 0.35f)),
    )
}

@Composable
fun RatingRow(rating: Double, modifier: Modifier = Modifier, reviewCount: Int? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(Icons.Filled.Star, contentDescription = null, tint = CartPilotColors.Accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Text(String.format(java.util.Locale.US, "%.1f", rating), style = MaterialTheme.typography.labelMedium)
        if (reviewCount != null) {
            Text(
                " (${formatCount(reviewCount)})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun StockBadge(stock: StockStatus, modifier: Modifier = Modifier) {
    val (bg, fg) = when (stock) {
        StockStatus.InStock -> CartPilotColors.PrimaryContainer to CartPilotColors.Primary
        StockStatus.LowStock -> CartPilotColors.Accent.copy(alpha = 0.18f) to Color(0xFF8A5A12)
        StockStatus.OutOfStock -> MaterialTheme.colorScheme.error.copy(alpha = 0.10f) to MaterialTheme.colorScheme.error
    }
    Text(
        stock.label,
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Card used in the product grid and in the chat recommendation carousel. */
@Composable
fun ProductCard(
    product: Product,
    onClick: () -> Unit,
    onAdd: (() -> Unit)?,
    modifier: Modifier = Modifier,
    adding: Boolean = false,
    selected: Boolean = false,
    badge: String? = null,
) {
    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.medium,
            )
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Box {
                ProductImage(product.imageUrl, product.name, Modifier.fillMaxWidth().aspectRatio(1.25f))
                if (badge != null) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier
                            .padding(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    product.categoryLabel.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    product.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(44.dp),
                )
                RatingRow(product.rating, reviewCount = product.reviewCount)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        formatUsd(product.price),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (onAdd != null) {
                        AddButton(enabled = product.available && !adding, loading = adding, onClick = onAdd)
                    }
                }
                if (product.stock != StockStatus.InStock) StockBadge(product.stock)
            }
        }
    }
}

@Composable
private fun AddButton(enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (enabled || loading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Icon(Icons.Outlined.AddShoppingCart, contentDescription = "Add to cart", tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProductDetailSheet(product: Product, onDismiss: () -> Unit, onAdd: () -> Unit, adding: Boolean) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProductImage(product.imageUrl, product.name, Modifier.fillMaxWidth().aspectRatio(1.6f).clip(MaterialTheme.shapes.medium))
            Text("${product.brand} · ${product.categoryLabel}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(product.name, style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(formatUsd(product.price), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                RatingRow(product.rating, reviewCount = product.reviewCount)
                StockBadge(product.stock)
            }
            Text(product.description, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                product.features.forEach { FeatureChip(it) }
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onAdd, enabled = product.available && !adding, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (adding) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.AddShoppingCart, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (product.available) "Add to cart" else "Out of stock")
                }
            }
        }
    }
}

@Composable
fun FeatureChip(text: String, highlighted: Boolean = false) {
    Text(
        text.replace('-', ' '),
        style = MaterialTheme.typography.labelMedium,
        color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (highlighted) CartPilotColors.PrimaryContainer else MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
