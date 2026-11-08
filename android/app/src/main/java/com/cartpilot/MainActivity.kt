package com.cartpilot

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cartpilot.core.AppContainer
import com.cartpilot.core.PaymentRedirect
import com.cartpilot.navigation.CartPilotNavHost
import com.cartpilot.ui.theme.CartPilotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Cold start from the PayPal hand-off page (process may have been killed while on PayPal).
        if (savedInstanceState == null) handlePaymentDeepLink(intent)
        setContent {
            CartPilotTheme {
                CartPilotNavHost()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePaymentDeepLink(intent)
    }

    /** Only forwards the event; the checkout screen verifies the payment with the backend. */
    private fun handlePaymentDeepLink(intent: Intent?) {
        PaymentRedirect.parse(intent?.data)?.let(AppContainer.events::postPaymentRedirect)
    }
}
