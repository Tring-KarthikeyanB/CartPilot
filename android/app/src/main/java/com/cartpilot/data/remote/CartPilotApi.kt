package com.cartpilot.data.remote

import com.cartpilot.data.model.HealthDto
import retrofit2.http.GET

/** Retrofit definition of the CartPilot FastAPI backend (paths are relative to BuildConfig.API_BASE_URL). */
interface CartPilotApi {
    @GET("api/v1/health")
    suspend fun health(): HealthDto
}
