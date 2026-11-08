package com.cartpilot.core

import com.cartpilot.data.remote.NetworkModule
import com.cartpilot.data.repository.SystemRepositoryImpl
import com.cartpilot.domain.repository.SystemRepository

/** Manual dependency container: one instance per process, no DI framework needed. */
object AppContainer {
    val systemRepository: SystemRepository by lazy { SystemRepositoryImpl(NetworkModule.api) }
}
