package com.cartpilot.data.repository

import com.cartpilot.core.AppResult
import com.cartpilot.core.safeApiCall
import com.cartpilot.data.remote.CartPilotApi
import com.cartpilot.domain.model.BackendStatus
import com.cartpilot.domain.repository.SystemRepository

class SystemRepositoryImpl(private val api: CartPilotApi) : SystemRepository {
    override suspend fun checkHealth(): AppResult<BackendStatus> = safeApiCall {
        val dto = api.health()
        check(dto.status == "ok") { "Server reported status '${dto.status}'." }
        BackendStatus(
            version = dto.version,
            environment = dto.environment,
            aiReady = dto.checks.geminiConfigured,
            paymentsReady = dto.checks.paypalConfigured,
        )
    }
}
