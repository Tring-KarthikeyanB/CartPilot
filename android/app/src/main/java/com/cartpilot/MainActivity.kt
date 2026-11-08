package com.cartpilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cartpilot.navigation.CartPilotNavHost
import com.cartpilot.ui.theme.CartPilotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CartPilotTheme {
                CartPilotNavHost()
            }
        }
    }
}
