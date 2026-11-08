package com.cartpilot.ui.success

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cartpilot.core.AppContainer
import com.cartpilot.core.AppResult
import com.cartpilot.domain.model.Receipt
import com.cartpilot.domain.repository.PaymentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SuccessUiState {
    data object Loading : SuccessUiState
    data class Loaded(val receipt: Receipt) : SuccessUiState
    data class Error(val message: String) : SuccessUiState
}

/** Shows only a backend-verified receipt (from the capture response or the order-status endpoint). */
class SuccessViewModel(private val orderId: String, private val payments: PaymentRepository) : ViewModel() {
    private val _state = MutableStateFlow<SuccessUiState>(SuccessUiState.Loading)
    val state: StateFlow<SuccessUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        payments.lastReceipt.value?.takeIf { it.orderId == orderId }?.let {
            _state.value = SuccessUiState.Loaded(it)
            return
        }
        _state.value = SuccessUiState.Loading
        viewModelScope.launch {
            _state.value = when (val r = payments.status(orderId)) {
                is AppResult.Success -> r.data.receipt?.takeIf { r.data.isCompleted }?.let { SuccessUiState.Loaded(it) }
                    ?: SuccessUiState.Error("This payment hasn't been completed.")
                is AppResult.Failure -> SuccessUiState.Error(r.message)
            }
        }
    }

    companion object {
        fun factory(orderId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { SuccessViewModel(orderId, AppContainer.paymentRepository) }
        }
    }
}
