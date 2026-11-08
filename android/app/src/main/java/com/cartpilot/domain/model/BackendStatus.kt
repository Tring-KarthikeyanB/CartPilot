package com.cartpilot.domain.model

data class BackendStatus(
    val version: String,
    val environment: String,
    val aiReady: Boolean,
    val paymentsReady: Boolean,
)
