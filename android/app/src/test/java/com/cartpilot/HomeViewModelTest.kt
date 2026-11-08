package com.cartpilot

import com.cartpilot.core.AppEvents
import com.cartpilot.core.AppResult
import com.cartpilot.domain.model.AgentAction
import com.cartpilot.domain.model.BackendStatus
import com.cartpilot.domain.model.ChatTurn
import com.cartpilot.ui.home.ChatMessage
import com.cartpilot.ui.home.ConnectionState
import com.cartpilot.ui.home.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val carts = FakeCartRepository()
    private val agent = FakeAgentRepository(carts)
    private val events = AppEvents()

    private fun vm(system: FakeSystemRepository = FakeSystemRepository()) = HomeViewModel(system, agent, carts, events)

    private fun turn(reply: String, cartUpdated: Boolean = true) = ChatTurn(
        reply = reply, products = emptyList(),
        actions = listOf(AgentAction("add_to_cart", true, "Added Keyline K84"), AgentAction("search_products", true, "Searched")),
        cart = sampleCart.copy(itemCount = 4, subtotal = 359.96), cartUpdated = cartUpdated, budget = null,
    )

    @Test
    fun `connection state reflects health check and recovers on retry`() {
        val system = FakeSystemRepository(AppResult.Failure("offline"))
        val vm = vm(system)
        assertEquals(ConnectionState.Offline("offline"), vm.state.value.connection)
        val status = BackendStatus("0.1.0", "dev", aiReady = true, paymentsReady = true)
        system.result = AppResult.Success(status)
        vm.checkConnection()
        assertEquals(ConnectionState.Connected(status), vm.state.value.connection)
    }

    @Test
    fun `sending a message appends user and assistant messages and updates cart badge`() {
        agent.results += AppResult.Success(turn("Here's your setup"))
        val vm = vm()
        vm.onInputChange("Build my setup under $500")
        vm.send()

        val messages = vm.state.value.messages
        assertEquals(2, messages.size)
        assertEquals("Build my setup under $500", (messages[0] as ChatMessage.User).text)
        val reply = messages[1] as ChatMessage.Assistant
        assertEquals("Here's your setup", reply.text)
        assertEquals(listOf("add_to_cart"), reply.actions.map { it.tool }) // search actions hidden
        assertEquals(359.96, reply.cart!!.subtotal, 0.001)
        assertEquals(4, vm.state.value.cartCount)
        assertEquals("", vm.state.value.input)
        assertFalse(vm.state.value.isSending)
    }

    @Test
    fun `failure shows retryable error and retry resends the same text`() {
        agent.results += AppResult.Failure("The AI service is busy", code = "ai_rate_limited")
        agent.results += AppResult.Success(turn("Recovered", cartUpdated = false))
        val vm = vm()
        vm.send("Find headphones below $100")
        val error = vm.state.value.messages.last() as ChatMessage.Error
        assertEquals("The AI service is busy", error.text)

        vm.retry(error)
        assertEquals(listOf("Find headphones below $100", "Find headphones below $100"), agent.sent)
        val messages = vm.state.value.messages
        assertEquals(2, messages.size) // user + assistant, error removed, no duplicate user bubble
        assertTrue(messages.last() is ChatMessage.Assistant)
        assertEquals(null, (messages.last() as ChatMessage.Assistant).cart)
    }

    @Test
    fun `blank messages are ignored`() {
        val vm = vm()
        vm.send("   ")
        assertTrue(vm.state.value.messages.isEmpty())
        assertTrue(agent.sent.isEmpty())
    }

    @Test
    fun `prompt posted by another screen is sent`() {
        agent.results += AppResult.Success(turn("Optimized"))
        val vm = vm()
        events.postPrompt("Optimize my cart")
        assertEquals(listOf("Optimize my cart"), agent.sent)
        assertEquals(null, events.pendingPrompt.value)
        assertEquals(2, vm.state.value.messages.size)
    }

    @Test
    fun `new conversation clears messages and resets backend history`() {
        agent.results += AppResult.Success(turn("hi"))
        val vm = vm()
        vm.send("hello")
        vm.newConversation()
        assertTrue(vm.state.value.messages.isEmpty())
        assertEquals(1, agent.resets)
    }
}
