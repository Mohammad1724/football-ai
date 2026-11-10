package com.footballai.app.data.remote

import com.footballai.app.core.AppError
import com.footballai.app.core.AppErrorException
import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeConstants
import com.footballai.app.domain.model.QuotaPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.pow

/**
 * Thin HTTP layer shared by all providers.
 *
 * Responsibilities: quota enforcement, timeouts, retry with exponential backoff,
 * honouring `Retry-After`, and mapping transport failures into [AppError] values.
 * No vendor-specific knowledge lives here.
 */
class ApiClient(
    private val quotaGuard: QuotaGuard,
    private val client: OkHttpClient = defaultClient(),
    private val maxRetries: Int = 3,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {

    suspend fun get(
        providerId: String,
        policy: QuotaPolicy,
        url: String,
        headers: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
    ): Outcome<String> {
        val parsedUrl = buildUrl(url, query)
            ?: return Outcome.Failure(AppError(ErrorCode.REQUEST_FAILED, detail = "Malformed URL: $url"))

        var attempt = 0
        var lastError: AppError? = null
        while (attempt <= maxRetries) {
            attempt++
            val result = try {
                quotaGuard.acquire(providerId, policy)
                execute(parsedUrl, headers)
            } catch (error: AppErrorException) {
                Outcome.Failure(error.error)
            } catch (error: UnknownHostException) {
                Outcome.Failure(AppError(ErrorCode.NETWORK_UNAVAILABLE, cause = error))
            } catch (error: SocketTimeoutException) {
                Outcome.Failure(AppError(ErrorCode.REQUEST_FAILED, detail = "Timeout", cause = error))
            } catch (error: IOException) {
                Outcome.Failure(AppError(ErrorCode.NETWORK_UNAVAILABLE, cause = error))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Outcome.Failure(AppError(ErrorCode.UNKNOWN, detail = error.message, cause = error))
            }

            when (result) {
                is Outcome.Success -> return result
                is Outcome.Failure -> {
                    lastError = result.error
                    val retryable = result.error.code in RETRYABLE
                    if (!retryable || attempt > maxRetries) return result
                    val backoff = (BASE_BACKOFF_MS * 2.0.pow((attempt - 1).toDouble())).toLong()
                    sleeper(result.error.retryAfterSeconds?.let { it * TimeConstants.SECOND_MS } ?: backoff)
                }
            }
        }
        return Outcome.Failure(lastError ?: AppError(ErrorCode.UNKNOWN))
    }

    private suspend fun execute(url: String, headers: Map<String, String>): Outcome<String> =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).get()
            headers.forEach { (key, value) -> builder.header(key, value) }
            try {
                client.newCall(builder.build()).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        Outcome.Success(body)
                    } else {
                        val retryAfter = response.header("Retry-After")?.toLongOrNull()
                            ?: response.header("X-RateLimit-Retry-After")?.toLongOrNull()
                        val code = when (response.code) {
                            401 -> ErrorCode.INVALID_API_KEY
                            403 -> ErrorCode.ACCESS_DENIED
                            404 -> ErrorCode.NOT_FOUND
                            429 -> ErrorCode.RATE_LIMITED
                            in 500..599 -> ErrorCode.REQUEST_FAILED
                            else -> ErrorCode.REQUEST_FAILED
                        }
                        Outcome.Failure(
                            AppError(
                                code = code,
                                httpStatus = response.code,
                                retryAfterSeconds = retryAfter,
                                // Never retain raw error bodies that might echo a token.
                                detail = null,
                            ),
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Outcome.Failure(AppError(ErrorCode.REQUEST_FAILED, detail = error.message, cause = error))
            }
        }

    private fun buildUrl(base: String, query: Map<String, String>): String? {
        val builder = base.toHttpUrlOrNull()?.newBuilder() ?: return null
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    companion object {
        private const val BASE_BACKOFF_MS = 800L
        private val RETRYABLE = setOf(
            ErrorCode.RATE_LIMITED,
            ErrorCode.REQUEST_FAILED,
            ErrorCode.NETWORK_UNAVAILABLE,
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
