package com.cartpilot.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cartpilot.ui.home.HomeRoute
import kotlinx.serialization.Serializable

/** Type-safe destinations. Products/Cart/Checkout/Success are added in later milestones. */
@Serializable
data object HomeDestination

@Composable
fun CartPilotNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = HomeDestination) {
        composable<HomeDestination> { HomeRoute() }
    }
}
