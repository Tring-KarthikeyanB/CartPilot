package com.cartpilot.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cartpilot.core.AppContainer
import com.cartpilot.core.AppResult
import com.cartpilot.domain.model.BackendStatus
import com.cartpilot.domain.repository.SystemRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ConnectionState {
    data object Checking : ConnectionState
    data class Connected(val status: BackendStatus) : ConnectionState
    data class Offline(val message: String) : ConnectionState
}

data class HomeUiState(
    val connection: ConnectionState = ConnectionState.Checking,
)

class HomeViewModel(private val systemRepository: SystemRepository) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        checkConnection()
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

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(AppContainer.systemRepository) }
        }
    }
}
