package com.cartpilot

import android.app.Application
import com.cartpilot.core.AppContainer

class CartPilotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
    }
}
