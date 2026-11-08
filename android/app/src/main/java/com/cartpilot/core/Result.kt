package com.cartpilot.core

import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException

/** Outcome of a repository call. UI layers map [Failure.message] directly to an error state. */
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(val message: String, val cause: Throwable? = null) : AppResult<Nothing>
}

/** Runs [block], converting network/HTTP exceptions into user-presentable [AppResult.Failure]s. */
suspend fun <T> safeApiCall(block: suspend () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    AppResult.Failure("Can't reach the CartPilot server. Check your connection and try again.", e)
} catch (e: HttpException) {
    AppResult.Failure("Server error (${e.code()}). Please try again.", e)
} catch (e: Exception) {
    AppResult.Failure(e.message ?: "Something went wrong.", e)
}
