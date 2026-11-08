package com.cartpilot.ui.cart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cartpilot.core.AppContainer
import com.cartpilot.core.AppEvents
import com.cartpilot.core.AppResult
import com.cartpilot.core.formatUsd
import com.cartpilot.domain.model.BudgetCheck
import com.cartpilot.domain.model.Cart
import com.cartpilot.domain.model.Saving
import com.cartpilot.domain.repository.CartRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CartUiState(
    val cart: Cart? = null,
    val budgetCheck: BudgetCheck? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val busyIds: Set<String> = emptySet(),
    val notice: String? = null,
    val editingBudget: Boolean = false,
)

/** User intents of the cart screen (implemented by [CartViewModel]). */
interface CartActions {
    fun refresh()
    fun increment(productId: String, current: Int)
    fun decrement(productId: String, current: Int)
    fun remove(productId: String, name: String)
    fun applySaving(saving: Saving)
    fun editBudget(show: Boolean)
    fun setBudget(amount: Double?)
    fun askAgentToOptimize()
}

class CartViewModel(private val carts: CartRepository, private val events: AppEvents) : ViewModel(), CartActions {

    private val _state = MutableStateFlow(CartUiState(cart = carts.cart.value))
    val state: StateFlow<CartUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { carts.cart.collect { c -> _state.update { it.copy(cart = c) } } }
        refresh()
    }

    override fun refresh() {
        _state.update { it.copy(loading = it.cart == null, error = null) }
        viewModelScope.launch {
            when (val r = carts.refresh()) {
                is AppResult.Success -> {
                    _state.update { it.copy(loading = false) }
                    loadBudgetCheck()
                }
                is AppResult.Failure -> _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    private suspend fun loadBudgetCheck() {
        val result = carts.budgetCheck()
        _state.update { it.copy(budgetCheck = (result as? AppResult.Success)?.data) }
    }

    private fun mutate(productId: String, successNotice: String? = null, op: suspend () -> AppResult<Cart>) {
        if (productId in _state.value.busyIds) return
        _state.update { it.copy(busyIds = it.busyIds + productId) }
        viewModelScope.launch {
            val result = op()
            _state.update {
                it.copy(
                    busyIds = it.busyIds - productId,
                    notice = when (result) {
                        is AppResult.Success -> successNotice
                        is AppResult.Failure -> result.message
                    },
                )
            }
            if (result is AppResult.Success) loadBudgetCheck()
        }
    }

    override fun increment(productId: String, current: Int) = mutate(productId) { carts.setQuantity(productId, current + 1) }

    override fun decrement(productId: String, current: Int) = mutate(productId) { carts.setQuantity(productId, current - 1) }

    override fun remove(productId: String, name: String) = mutate(productId, "Removed $name") { carts.remove(productId) }

    override fun applySaving(saving: Saving) = mutate(saving.replaceProductId, "Swapped to ${saving.withName}, saving ${formatUsd(saving.savings)}") {
        carts.replace(saving.replaceProductId, saving.withProductId)
    }

    override fun editBudget(show: Boolean) = _state.update { it.copy(editingBudget = show) }

    override fun setBudget(amount: Double?) {
        _state.update { it.copy(editingBudget = false) }
        viewModelScope.launch {
            when (val r = carts.setBudget(amount)) {
                is AppResult.Success -> loadBudgetCheck()
                is AppResult.Failure -> _state.update { it.copy(notice = r.message) }
            }
        }
    }

    /** Hands the cart to the AI agent; the chat screen picks this prompt up. */
    override fun askAgentToOptimize() {
        val budget = _state.value.cart?.budget
        events.postPrompt(
            if (budget != null) "Optimize my cart so it stays within my ${formatUsd(budget)} budget without losing quality. Explain the changes."
            else "Review my cart and suggest how to get better value for money.",
        )
    }

    fun noticeShown() = _state.update { it.copy(notice = null) }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { CartViewModel(AppContainer.cartRepository, AppContainer.events) }
        }
    }
}
