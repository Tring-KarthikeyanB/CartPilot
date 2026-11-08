package com.cartpilot

import com.cartpilot.core.AppResult
import com.cartpilot.domain.model.BackendStatus
import com.cartpilot.domain.repository.SystemRepository
import com.cartpilot.ui.home.ConnectionState
import com.cartpilot.ui.home.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeRepo(var result: AppResult<BackendStatus>) : SystemRepository {
        override suspend fun checkHealth() = result
    }

    @Test
    fun `connected state when health succeeds`() {
        val status = BackendStatus("0.1.0", "development", aiReady = true, paymentsReady = false)
        val vm = HomeViewModel(FakeRepo(AppResult.Success(status)))
        assertEquals(ConnectionState.Connected(status), vm.state.value.connection)
    }

    @Test
    fun `offline state then recovers on retry`() {
        val repo = FakeRepo(AppResult.Failure("Can't reach the CartPilot server."))
        val vm = HomeViewModel(repo)
        assertEquals(ConnectionState.Offline("Can't reach the CartPilot server."), vm.state.value.connection)

        val status = BackendStatus("0.1.0", "development", aiReady = false, paymentsReady = false)
        repo.result = AppResult.Success(status)
        vm.checkConnection()
        assertEquals(ConnectionState.Connected(status), vm.state.value.connection)
    }
}
