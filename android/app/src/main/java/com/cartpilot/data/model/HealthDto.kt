package com.cartpilot.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HealthDto(
    val status: String,
    val service: String,
    val version: String,
    val environment: String,
    val checks: HealthChecksDto,
)

@Serializable
data class HealthChecksDto(
    @SerialName("gemini_configured") val geminiConfigured: Boolean,
    @SerialName("paypal_configured") val paypalConfigured: Boolean,
)
