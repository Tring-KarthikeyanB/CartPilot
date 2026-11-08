package com.cartpilot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.cartpilot.data.model.BudgetCheckDto
import com.cartpilot.data.model.CartDto
import com.cartpilot.data.model.ComparisonDto
import com.cartpilot.data.model.ProductDto
import com.cartpilot.data.model.ReceiptDto
import com.cartpilot.data.remote.NetworkModule
import com.cartpilot.data.repository.toDomain
import com.cartpilot.domain.model.AgentAction
import com.cartpilot.domain.model.BackendStatus
import com.cartpilot.domain.model.Category
import com.cartpilot.domain.model.Product
import com.cartpilot.domain.model.Saving
import com.cartpilot.domain.model.categoryLabel
import com.cartpilot.ui.cart.CartActions
import com.cartpilot.ui.cart.CartScreen
import com.cartpilot.ui.cart.CartUiState
import com.cartpilot.ui.checkout.CheckoutActions
import com.cartpilot.ui.checkout.CheckoutScreen
import com.cartpilot.ui.checkout.CheckoutStage
import com.cartpilot.ui.checkout.CheckoutUiState
import com.cartpilot.ui.home.ChatMessage
import com.cartpilot.ui.home.ConnectionState
import com.cartpilot.ui.home.HomeScreen
import com.cartpilot.ui.home.HomeUiState
import com.cartpilot.ui.products.ProductsActions
import com.cartpilot.ui.products.ProductsScreen
import com.cartpilot.ui.products.ProductsUiState
import com.cartpilot.ui.success.SuccessScreen
import com.cartpilot.ui.success.SuccessUiState
import com.cartpilot.ui.theme.CartPilotTheme
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real Compose screens (stateless *Screen composables + app theme) on the JVM and writes
 * PNGs to docs/screenshots. Data: catalogue/cart/budget/comparison come from real backend responses
 * (seed + fixtures), the chat reply is the text Gemini produced in the M3 live test, and the receipt is the
 * fake-PayPal fixture (order id ORDER00001TEST). Device captures should replace these for the final video.
 *
 *   ./gradlew testDebugUnitTest -PrecordScreenshots --tests "*ScreenshotTest*"
 */
@OptIn(ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val json = NetworkModule.json
    private val repoRoot = File("../..").canonicalFile
    private val out = File(repoRoot, "docs/screenshots")

    private fun fixture(name: String) = requireNotNull(javaClass.classLoader?.getResource("fixtures/$name.json")).readText()

    private val catalogue: List<Product> by lazy {
        json.decodeFromString<List<ProductDto>>(File(repoRoot, "backend/seed/products.json").readText()).map { it.toDomain() }
    }
    private fun product(id: String) = catalogue.first { it.id == id }

    private val imageHandler = AsyncImagePreviewHandler { request ->
        val name = request.data.toString().substringAfterLast('/')
        val file = File(repoRoot, "backend/static/products/$name")
        (if (file.exists()) BitmapFactory.decodeFile(file.path) else null ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)).asImage()
    }

    private fun shot(name: String, wholeScreen: Boolean = false, content: @Composable () -> Unit) {
        out.mkdirs()
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides imageHandler) {
                CartPilotTheme { content() }
            }
        }
        compose.waitForIdle()
        val path = File(out, "$name.png").path
        if (wholeScreen) captureScreenRoboImage(path) else compose.onRoot().captureRoboImage(path)
    }

    private val online = ConnectionState.Connected(BackendStatus("0.1.0", "production", aiReady = true, paymentsReady = true))

    // Real agent output from the M3 live Gemini run (docs/PLAN.md, M3).
    private val agentReply = """
        I have put together a great home office setup for you:

        • Forge TKL Mechanical Keyboard (Brown Switches) (${'$'}89.99)
        • Precision Pro Ergonomic Mouse (${'$'}59.99)
        • Halo ANC Wireless Headphones (${'$'}149.99)
        • Elevate Pro Adjustable Laptop Stand (${'$'}59.99)

        Subtotal: ${'$'}359.96
        Remaining budget: ${'$'}140.04 (${'$'}500.00 budget)
    """.trimIndent()

    private val builtCart by lazy {
        json.decodeFromString<CartDto>(fixture("screenshot_cart")).toDomain()
            .copy(budget = 500.0, budgetRemaining = 140.04, overBudget = false)
    }

    @Test
    fun home_welcome() = shot("01-home") {
        HomeScreen(
            state = HomeUiState(connection = online),
            onInputChange = {}, onSend = {}, onPrompt = {}, onRetry = {}, onRetryConnection = {},
            onNewConversation = {}, onProductClick = {}, onOpenCart = {}, onBrowse = {},
        )
    }

    @Test
    fun home_agent_built_cart() = shot("02-agent-chat") {
        HomeScreen(
            state = HomeUiState(
                connection = online,
                cartCount = 4,
                messages = listOf(
                    ChatMessage.User(0, "I need a home office setup. My budget is \$500. I need a keyboard, mouse, headphones and laptop stand."),
                    ChatMessage.Assistant(
                        1, agentReply,
                        products = listOf("kb-003", "ms-002", "hp-003", "ls-002").map(::product),
                        actions = listOf(
                            AgentAction("add_to_cart", true, "Added Forge TKL Mechanical Keyboard (Brown Switches)"),
                            AgentAction("add_to_cart", true, "Added Precision Pro Ergonomic Mouse"),
                            AgentAction("add_to_cart", true, "Added Halo ANC Wireless Headphones"),
                            AgentAction("add_to_cart", true, "Added Elevate Pro Adjustable Laptop Stand"),
                        ),
                        cart = builtCart,
                    ),
                ),
            ),
            onInputChange = {}, onSend = {}, onPrompt = {}, onRetry = {}, onRetryConnection = {},
            onNewConversation = {}, onProductClick = {}, onOpenCart = {}, onBrowse = {},
        )
    }

    private val categories by lazy {
        catalogue.groupBy { it.category }.map { (id, items) -> Category(id, categoryLabel(id), items.size) }
    }

    @Test
    fun discover() = shot("03-discover") {
        ProductsScreen(
            state = ProductsUiState(
                categories = categories, category = "headphones", loading = false, cartCount = 4,
                products = catalogue.filter { it.category == "headphones" },
            ),
            actions = NoProductsActions, onBack = {}, onOpenCart = {},
        )
    }

    @Test
    fun compare() = shot("04-compare", wholeScreen = true) {
        ProductsScreen(
            state = ProductsUiState(
                categories = categories, category = "keyboards", loading = false, compareMode = true,
                compareIds = listOf("kb-003", "kb-005", "kb-006"),
                products = catalogue.filter { it.category == "keyboards" },
                comparison = json.decodeFromString<ComparisonDto>(fixture("compare")).toDomain(),
            ),
            actions = NoProductsActions, onBack = {}, onOpenCart = {},
        )
    }

    @Test
    fun smart_cart_over_budget() = shot("05-smart-cart") {
        CartScreen(
            state = CartUiState(
                cart = json.decodeFromString<CartDto>(fixture("screenshot_cart")).toDomain(),
                budgetCheck = json.decodeFromString<BudgetCheckDto>(fixture("screenshot_budget")).toDomain(),
                loading = false,
            ),
            actions = NoCartActions, onBack = {}, onBrowse = {}, onCheckout = {}, onAskAgent = {},
        )
    }

    @Test
    fun checkout_review() = shot("06-checkout") {
        CheckoutScreen(
            state = CheckoutUiState(cart = builtCart, loadingCart = false, approved = true),
            actions = NoCheckoutActions, onBack = {},
        )
    }

    @Test
    fun checkout_awaiting_paypal() = shot("07-paypal-approval") {
        CheckoutScreen(
            state = CheckoutUiState(
                cart = builtCart, loadingCart = false, approved = true,
                stage = CheckoutStage.AwaitingApproval("ORDER00001TEST", "https://www.sandbox.paypal.com/checkoutnow?token=ORDER00001TEST"),
            ),
            actions = NoCheckoutActions, onBack = {},
        )
    }

    @Test
    fun payment_success() = shot("08-success") {
        SuccessScreen(
            state = SuccessUiState.Loaded(json.decodeFromString<ReceiptDto>(fixture("receipt")).toDomain()),
            onRetry = {}, onDone = {},
        )
    }

    private object NoProductsActions : ProductsActions {
        override fun onQueryChange(query: String) = Unit
        override fun onCategory(category: String?) = Unit
        override fun retry() = Unit
        override fun toggleCompareMode() = Unit
        override fun toggleCompare(product: Product) = Unit
        override fun compare() = Unit
        override fun dismissComparison() = Unit
        override fun selectProduct(product: Product?) = Unit
        override fun addToCart(product: Product) = Unit
    }

    private object NoCartActions : CartActions {
        override fun refresh() = Unit
        override fun increment(productId: String, current: Int) = Unit
        override fun decrement(productId: String, current: Int) = Unit
        override fun remove(productId: String, name: String) = Unit
        override fun applySaving(saving: Saving) = Unit
        override fun editBudget(show: Boolean) = Unit
        override fun setBudget(amount: Double?) = Unit
        override fun askAgentToOptimize() = Unit
    }

    private object NoCheckoutActions : CheckoutActions {
        override fun setApproved(approved: Boolean) = Unit
        override fun pay() = Unit
        override fun checkAgain() = Unit
        override fun retryCapture(orderId: String) = Unit
        override fun reopenPayPal() = Unit
        override fun startOver() = Unit
    }
}
