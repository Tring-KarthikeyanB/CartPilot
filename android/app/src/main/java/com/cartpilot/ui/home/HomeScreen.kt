package com.cartpilot.ui.home

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cartpilot.domain.model.BackendStatus
import com.cartpilot.ui.theme.CartPilotColors
import com.cartpilot.ui.theme.CartPilotTheme

@Composable
fun HomeRoute(viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeScreen(state = state, onRetry = viewModel::checkConnection)
}

@Composable
fun HomeScreen(state: HomeUiState, onRetry: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Color.White)
                }
                Spacer(Modifier.size(12.dp))
                Column {
                    Text("CartPilot AI", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Your personal AI shopping agent",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
            Text(
                "From intent to checkout.",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(24.dp))
            ConnectionCard(state.connection, onRetry)
        }
    }
}

@Composable
private fun ConnectionCard(connection: ConnectionState, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Backend connection", style = MaterialTheme.typography.titleMedium)
            when (connection) {
                ConnectionState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text("Connecting…", style = MaterialTheme.typography.bodyMedium)
                }
                is ConnectionState.Connected -> {
                    StatusLine(ok = true, label = "Server online · v${connection.status.version} (${connection.status.environment})")
                    StatusLine(ok = connection.status.aiReady, label = if (connection.status.aiReady) "AI agent ready" else "AI agent not configured")
                    StatusLine(ok = connection.status.paymentsReady, label = if (connection.status.paymentsReady) "PayPal Sandbox ready" else "PayPal not configured")
                }
                is ConnectionState.Offline -> {
                    StatusLine(ok = false, label = connection.message)
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(if (ok) CartPilotColors.Primary else CartPilotColors.Accent, CircleShape),
        )
        Spacer(Modifier.size(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    CartPilotTheme {
        HomeScreen(
            state = HomeUiState(ConnectionState.Connected(BackendStatus("0.1.0", "development", aiReady = true, paymentsReady = false))),
            onRetry = {},
        )
    }
}
