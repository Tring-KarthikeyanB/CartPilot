package com.cartpilot

import com.cartpilot.core.AppEvents
import com.cartpilot.core.AppResult
import com.cartpilot.core.PaymentRedirect
import com.cartpilot.domain.model.OrderStatus
import com.cartpilot.ui.checkout.CheckoutEffect
import com.cartpilot.ui.checkout.CheckoutStage
import com.cartpilot.ui.checkout.CheckoutViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CheckoutViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private val carts = FakeCartRepository()
    private val payments = FakePaymentRepository()
    private val events = AppEvents()

    private fun TestScope.newVm(): Pair<CheckoutViewModel, MutableList<CheckoutEffect>> {
        val vm = CheckoutViewModel(carts, payments, events)
        val effects = mutableListOf<CheckoutEffect>()
        // Unconfined: receive effects immediately (advanceUntilIdle doesn't wait for backgroundScope work).
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.toList(effects) }
        advanceUntilIdle()
        return vm to effects
    }

    @Test
    fun `cannot pay without explicit approval`() = runTest(dispatcher) {
        val (vm, _) = newVm()
        assertFalse(vm.state.value.canPay)
        vm.pay()
        advanceUntilIdle()
        assertTrue(payments.createdWithTotals.isEmpty())
    }

    @Test
    fun `approved payment creates order with displayed total and opens PayPal`() = runTest(dispatcher) {
        val (vm, effects) = newVm()
        vm.setApproved(true)
        vm.pay()
        advanceUntilIdle()
        assertEquals(listOf(79.99), payments.createdWithTotals)
        assertEquals(CheckoutStage.AwaitingApproval("ORDER1", "https://paypal/approve"), vm.state.value.stage)
        assertEquals(listOf<CheckoutEffect>(CheckoutEffect.OpenUrl("https://paypal/approve")), effects)
    }

    @Test
    fun `return deep link triggers backend capture and success only after verification`() = runTest(dispatcher) {
        val (vm, effects) = newVm()
        vm.setApproved(true)
        vm.pay()
        advanceUntilIdle()

        events.postPaymentRedirect(PaymentRedirect(PaymentRedirect.Kind.Return, "ORDER1"))
        advanceUntilIdle()
        assertEquals(listOf("ORDER1"), payments.captured)
        assertEquals(CheckoutEffect.Completed("ORDER1"), effects.last())
        assertEquals(null, events.paymentRedirect.value) // consumed
    }

    @Test
    fun `failed verification never reports success`() = runTest(dispatcher) {
        payments.captureResult = { AppResult.Failure("We couldn't verify this payment", code = "payment_verification_failed") }
        val (vm, effects) = newVm()
        vm.setApproved(true)
        vm.pay()
        advanceUntilIdle()
        events.postPaymentRedirect(PaymentRedirect(PaymentRedirect.Kind.Return, "ORDER1"))
        advanceUntilIdle()
        assertTrue(effects.none { it is CheckoutEffect.Completed })
        assertEquals(CheckoutStage.Failed("We couldn't verify this payment", retryCaptureOf = "ORDER1"), vm.state.value.stage)
    }

    @Test
    fun `cancel deep link shows cancelled state without capturing`() = runTest(dispatcher) {
        val (vm, _) = newVm()
        vm.setApproved(true)
        vm.pay()
        advanceUntilIdle()
        events.postPaymentRedirect(PaymentRedirect(PaymentRedirect.Kind.Cancel, "ORDER1"))
        advanceUntilIdle()
        val stage = vm.state.value.stage as CheckoutStage.NotCompleted
        assertEquals("Payment cancelled", stage.title)
        assertTrue(payments.captured.isEmpty())

        vm.startOver()
        assertEquals(CheckoutStage.Review, vm.state.value.stage)
        assertEquals(null, payments.pendingOrderId)
    }

    @Test
    fun `stale total refreshes cart and requires re-approval`() = runTest(dispatcher) {
        payments.createResult = AppResult.Failure(
            "Your cart total changed", code = "cart_changed", details = buildJsonObject { put("current_total", JsonPrimitive(99.0)) },
        )
        val (vm, _) = newVm()
        val refreshesBefore = carts.refreshCount
        vm.setApproved(true)
        vm.pay()
        advanceUntilIdle()
        assertEquals(CheckoutStage.Review, vm.state.value.stage)
        assertFalse(vm.state.value.approved)
        assertEquals("Your cart total changed", vm.state.value.notice)
        assertTrue(carts.refreshCount > refreshesBefore)
    }

    @Test
    fun `resume without deep link checks status and captures when approved`() = runTest(dispatcher) {
        val (vm, effects) = newVm()
        vm.setApproved(true)
        vm.pay()
        advanceUntilIdle()
        payments.statusResult = { AppResult.Success(OrderStatus(it, "awaiting_approval", "APPROVED", true, "u", 79.99, null)) }

        vm.onResumed()
        advanceTimeBy(1_000)
        advanceUntilIdle()
        assertEquals(1, payments.statusChecks)
        assertEquals(listOf("ORDER1"), payments.captured)
        assertEquals(CheckoutEffect.Completed("ORDER1"), effects.last())
    }

    @Test
    fun `resume when buyer did not approve shows not completed`() = runTest(dispatcher) {
        val (vm, _) = newVm()
        vm.setApproved(true)
        vm.pay()
        advanceUntilIdle()
        vm.onResumed()
        advanceTimeBy(1_000)
        advanceUntilIdle()
        val stage = vm.state.value.stage as CheckoutStage.NotCompleted
        assertEquals("Payment not completed", stage.title)
        assertTrue(payments.captured.isEmpty())
    }

    @Test
    fun `deep link arriving during resume does not double capture`() = runTest(dispatcher) {
        val (vm, _) = newVm()
        vm.setApproved(true)
        vm.pay()
        advanceUntilIdle()
        events.postPaymentRedirect(PaymentRedirect(PaymentRedirect.Kind.Return, "ORDER1"))
        vm.onResumed()
        advanceTimeBy(1_000)
        advanceUntilIdle()
        assertEquals(listOf("ORDER1"), payments.captured)
        assertEquals(0, payments.statusChecks)
    }

    @Test
    fun `pending order after app restart is resumed via status`() = runTest(dispatcher) {
        payments.pendingOrderId = "ORDER9"
        payments.statusResult = { AppResult.Success(OrderStatus(it, "completed", "COMPLETED", false, null, 79.99, receipt(it))) }
        val (_, effects) = newVm()
        assertEquals(CheckoutEffect.Completed("ORDER9"), effects.last())
        assertTrue(payments.captured.isEmpty())
    }

    @Test
    fun `not approved capture response offers to continue in PayPal`() = runTest(dispatcher) {
        payments.captureResult = {
            AppResult.Failure("not approved", code = "payment_not_approved", details = buildJsonObject { put("approve_url", JsonPrimitive("https://pp/again")) })
        }
        val (vm, effects) = newVm()
        vm.setApproved(true)
        vm.pay()
        advanceUntilIdle()
        events.postPaymentRedirect(PaymentRedirect(PaymentRedirect.Kind.Return, "ORDER1"))
        advanceUntilIdle()
        val stage = vm.state.value.stage as CheckoutStage.NotCompleted
        assertEquals("https://pp/again", stage.approveUrl)
        vm.reopenPayPal()
        advanceUntilIdle()
        assertEquals(CheckoutEffect.OpenUrl("https://pp/again"), effects.last())
    }
}
