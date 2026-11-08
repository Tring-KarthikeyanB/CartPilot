package com.cartpilot.core

import com.cartpilot.data.model.ErrorEnvelopeDto
import com.cartpilot.data.remote.NetworkModule
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException

/** Outcome of a repository call. UI layers map [Failure.message] directly to an error state. */
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>

    /** [code] is the backend's stable error code (e.g. "cart_changed", "ai_rate_limited"), when available. */
    data class Failure(
        val message: String,
        val code: String? = null,
        val details: JsonObject? = null,
        val cause: Throwable? = null,
    ) : AppResult<Nothing> {
        fun detailString(key: String): String? = (details?.get(key) as? JsonPrimitive)?.content
        fun detailDouble(key: String): Double? = (details?.get(key) as? JsonPrimitive)?.doubleOrNull
    }
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(data)
    return this
}

/** Runs [block], converting network/HTTP exceptions into user-presentable [AppResult.Failure]s. */
suspend fun <T> safeApiCall(block: suspend () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: SocketTimeoutException) {
    AppResult.Failure("The server took too long to respond. Please try again.", code = "timeout", cause = e)
} catch (e: IOException) {
    AppResult.Failure("Can't reach the CartPilot server. Check your connection and try again.", code = "network", cause = e)
} catch (e: HttpException) {
    val envelope = runCatching {
        e.response()?.errorBody()?.string()?.let { NetworkModule.json.decodeFromString<ErrorEnvelopeDto>(it) }
    }.getOrNull()
    if (envelope != null) {
        AppResult.Failure(envelope.error.message, envelope.error.code, envelope.error.detailsObject, e)
    } else {
        AppResult.Failure("Server error (${e.code()}). Please try again.", code = "http_${e.code()}", cause = e)
    }
} catch (e: Exception) {
    AppResult.Failure(e.message ?: "Something went wrong.", cause = e)
}
