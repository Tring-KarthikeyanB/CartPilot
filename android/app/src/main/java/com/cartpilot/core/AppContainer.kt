package com.cartpilot.core

import android.content.Context
import com.cartpilot.data.remote.NetworkModule
import com.cartpilot.data.repository.AgentRepositoryImpl
import com.cartpilot.data.repository.CartRepositoryImpl
import com.cartpilot.data.repository.PaymentRepositoryImpl
import com.cartpilot.data.repository.ProductRepositoryImpl
import com.cartpilot.data.repository.SystemRepositoryImpl
import com.cartpilot.domain.repository.AgentRepository
import com.cartpilot.domain.repository.CartRepository
import com.cartpilot.domain.repository.PaymentRepository
import com.cartpilot.domain.repository.ProductRepository
import com.cartpilot.domain.repository.SystemRepository

/** Manual dependency container: one instance per process, no DI framework needed. */
object AppContainer {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val events = AppEvents()
    val session: SessionStore by lazy { PrefsSessionStore(appContext) }

    val systemRepository: SystemRepository by lazy { SystemRepositoryImpl(NetworkModule.api) }
    val productRepository: ProductRepository by lazy { ProductRepositoryImpl(NetworkModule.api) }
    val cartRepository: CartRepository by lazy { CartRepositoryImpl(NetworkModule.api, session) }
    val agentRepository: AgentRepository by lazy { AgentRepositoryImpl(NetworkModule.api, session, cartRepository) }
    val paymentRepository: PaymentRepository by lazy { PaymentRepositoryImpl(NetworkModule.api, session, cartRepository) }
}
