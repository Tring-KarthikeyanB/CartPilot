package com.cartpilot.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.cartpilot.core.AppContainer
import com.cartpilot.ui.cart.CartRoute
import com.cartpilot.ui.checkout.CheckoutRoute
import com.cartpilot.ui.home.HomeRoute
import com.cartpilot.ui.products.ProductsRoute
import com.cartpilot.ui.success.SuccessRoute
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.Serializable

@Serializable data object HomeDestination
@Serializable data class ProductsDestination(val category: String? = null)
@Serializable data object CartDestination
@Serializable data object CheckoutDestination
@Serializable data class SuccessDestination(val orderId: String)

@Composable
fun CartPilotNavHost(navController: NavHostController = rememberNavController()) {
    // A PayPal redirect can arrive while the user is anywhere (or on a cold start): make sure the
    // checkout screen is showing so it can verify the payment.
    LaunchedEffect(navController) {
        AppContainer.events.paymentRedirect.filterNotNull().collect {
            val onCheckout = navController.currentBackStackEntry?.destination?.route?.contains("CheckoutDestination") == true
            if (!onCheckout) navController.navigate(CheckoutDestination) { launchSingleTop = true }
        }
    }

    NavHost(
        navController = navController,
        startDestination = HomeDestination,
        enterTransition = { slideInHorizontally { it / 6 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 6 } + fadeOut() },
    ) {
        composable<HomeDestination> {
            HomeRoute(
                onOpenCart = { navController.navigate(CartDestination) { launchSingleTop = true } },
                onBrowse = { navController.navigate(ProductsDestination()) { launchSingleTop = true } },
            )
        }
        composable<ProductsDestination> { entry ->
            ProductsRoute(
                initialCategory = entry.toRoute<ProductsDestination>().category,
                onBack = { navController.popBackStack() },
                onOpenCart = { navController.navigate(CartDestination) { launchSingleTop = true } },
            )
        }
        composable<CartDestination> {
            CartRoute(
                onBack = { navController.popBackStack(HomeDestination, inclusive = false) },
                onBrowse = { navController.navigate(ProductsDestination()) { launchSingleTop = true } },
                onCheckout = { navController.navigate(CheckoutDestination) { launchSingleTop = true } },
                onAskAgent = { navController.popBackStack(HomeDestination, inclusive = false) },
            )
        }
        composable<CheckoutDestination> {
            CheckoutRoute(
                onBack = { navController.popBackStack() },
                onSuccess = { orderId ->
                    navController.navigate(SuccessDestination(orderId)) {
                        popUpTo(HomeDestination) { inclusive = false }
                    }
                },
            )
        }
        composable<SuccessDestination> { entry ->
            SuccessRoute(
                orderId = entry.toRoute<SuccessDestination>().orderId,
                onDone = { navController.popBackStack(HomeDestination, inclusive = false) },
            )
        }
    }
}
