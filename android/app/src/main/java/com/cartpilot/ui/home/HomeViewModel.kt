package com.cartpilot.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cartpilot.core.AppContainer
import com.cartpilot.core.AppEvents
import com.cartpilot.core.AppResult
import com.cartpilot.domain.model.AgentAction
import com.cartpilot.domain.model.BackendStatus
import com.cartpilot.domain.model.Cart
import com.cartpilot.domain.model.Product
import com.cartpilot.domain.repository.AgentRepository
import com.cartpilot.domain.repository.CartRepository
import com.cartpilot.domain.repository.SystemRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ConnectionState {
    data object Checking : ConnectionState
    data class Connected(val status: BackendStatus) : ConnectionState
    data class Offline(val message: String) : ConnectionState
}

sealed interface ChatMessage {
    val id: Long

    data class User(override val id: Long, val text: String) : ChatMessage

    data class Assistant(
        override val id: Long,
        val text: String,
        val products: List<Product> = emptyList(),
        val actions: List<AgentAction> = emptyList(),
        /** Cart snapshot when this turn changed the cart (shown as a summary pill). */
        val cart: Cart? = null,
    ) : ChatMessage

    data class Error(override val id: Long, val text: String, val retryText: String) : ChatMessage
}

data class HomeUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isSending: Boolean = false,
    val connection: ConnectionState = ConnectionState.Checking,
    val cartCount: Int = 0,
    val selectedProduct: Product? = null,
    val addingToCart: Boolean = false,
    val notice: String? = null,
) {
    val canSend: Boolean get() = input.isNotBlank() && !isSending
}

val SuggestedPrompts = listOf(
    "Build my home office setup under \$500: keyboard, mouse, headphones and laptop stand.",
    "Find headphones below \$100.",
    "Compare mechanical keyboards.",
    "Help me optimize my shopping budget.",
)

class HomeViewModel(
    private val systemRepository: SystemRepository,
    private val agentRepository: AgentRepository,
    private val cartRepository: CartRepository,
    private val events: AppEvents,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()
    private var nextId = 0L

    init {
        checkConnection()
        viewModelScope.launch { cartRepository.refresh() }
        viewModelScope.launch {
            cartRepository.cart.collect { cart -> _state.update { it.copy(cartCount = cart?.itemCount ?: 0) } }
        }
        viewModelScope.launch {
            events.pendingPrompt.filterNotNull().collect {
                events.consumePrompt()?.let { prompt -> send(prompt) }
            }
        }
    }

    fun checkConnection() {
        _state.update { it.copy(connection = ConnectionState.Checking) }
        viewModelScope.launch {
            val connection = when (val result = systemRepository.checkHealth()) {
                is AppResult.Success -> ConnectionState.Connected(result.data)
                is AppResult.Failure -> ConnectionState.Offline(result.message)
            }
            _state.update { it.copy(connection = connection) }
        }
    }

    fun onInputChange(text: String) = _state.update { it.copy(input = text.take(2000)) }

    fun send(text: String = _state.value.input) {
        val message = text.trim()
        if (message.isEmpty() || _state.value.isSending) return
        _state.update { it.copy(messages = it.messages + ChatMessage.User(nextId++, message), input = "") }
        ask(message)
    }

    fun retry(error: ChatMessage.Error) {
        if (_state.value.isSending) return
        _state.update { s -> s.copy(messages = s.messages.filterNot { it.id == error.id }) }
        ask(error.retryText)
    }

    private fun ask(message: String) {
        _state.update { it.copy(isSending = true) }
        viewModelScope.launch {
            val reply: ChatMessage = when (val result = agentRepository.chat(message)) {
                is AppResult.Success -> with(result.data) {
                    ChatMessage.Assistant(nextId++, reply, products, actions.filter { it.ok && it.tool in VisibleTools }, cart.takeIf { cartUpdated })
                }
                is AppResult.Failure -> ChatMessage.Error(nextId++, result.message, message)
            }
            _state.update { it.copy(messages = it.messages + reply, isSending = false) }
        }
    }

    fun selectProduct(product: Product?) = _state.update { it.copy(selectedProduct = product) }

    /** Direct add from a product card (no LLM round trip). */
    fun addSelectedToCart() {
        val product = _state.value.selectedProduct ?: return
        _state.update { it.copy(addingToCart = true) }
        viewModelScope.launch {
            val result = cartRepository.add(product.id)
            _state.update {
                when (result) {
                    is AppResult.Success -> it.copy(addingToCart = false, selectedProduct = null, notice = "Added ${product.name}")
                    is AppResult.Failure -> it.copy(addingToCart = false, notice = result.message)
                }
            }
        }
    }

    fun noticeShown() = _state.update { it.copy(notice = null) }

    fun newConversation() {
        if (_state.value.isSending) return
        _state.update { it.copy(messages = emptyList(), input = "") }
        viewModelScope.launch { agentRepository.resetConversation() }
    }

    companion object {
        private val VisibleTools = setOf("add_to_cart", "remove_from_cart", "replace_product", "compare_products")

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    AppContainer.systemRepository, AppContainer.agentRepository, AppContainer.cartRepository, AppContainer.events,
                )
            }
        }
    }
}
