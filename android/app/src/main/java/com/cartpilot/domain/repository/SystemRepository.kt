package com.cartpilot.domain.repository

import com.cartpilot.core.AppResult
import com.cartpilot.domain.model.BackendStatus

interface SystemRepository {
    suspend fun checkHealth(): AppResult<BackendStatus>
}
