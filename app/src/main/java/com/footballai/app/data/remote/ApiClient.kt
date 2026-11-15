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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
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
    quotaGuard: QuotaGuard,
    client: OkHttpClient = defaultClient(),
    private val maxRetries: Int = 3,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {

    /**
     * The quota is spent in a *network* interceptor, not before the call: a document the
     * cache can answer never leaves the device, so it must not consume the provider's
     * budget or wait for a token. Counting cache hits was what kept a refresh slow even
     * after the downloads themselves were cached.
     */
    private val client: OkHttpClient = client.newBuilder()
        .addNetworkInterceptor(QuotaInterceptor(quotaGuard))
        .build()

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
                execute(parsedUrl, quotaHeaders(providerId, policy) + headers)
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
            } catch (error: AppErrorException) {
                // Thrown by the quota interceptor: the provider budget is spent.
                throw error
            } catch (error: UnknownHostException) {
                Outcome.Failure(AppError(ErrorCode.NETWORK_UNAVAILABLE, detail = "Host could not be resolved", cause = error))
            } catch (error: SocketTimeoutException) {
                Outcome.Failure(AppError(ErrorCode.REQUEST_FAILED, detail = "Connection timed out", cause = error))
            } catch (error: IOException) {
                Outcome.Failure(AppError(ErrorCode.NETWORK_UNAVAILABLE, detail = error.message, cause = error))
            } catch (error: Exception) {
                Outcome.Failure(AppError(ErrorCode.UNKNOWN, detail = error.message, cause = error))
            }
        }

    /** The provider and its published limits, carried to the interceptor. */
    private fun quotaHeaders(providerId: String, policy: QuotaPolicy): Map<String, String> = mapOf(
        HEADER_QUOTA_PROVIDER to providerId,
        HEADER_QUOTA_MINUTE to policy.requestsPerMinute.toString(),
        HEADER_QUOTA_DAY to policy.requestsPerDay.toString(),
    )

    /**
     * Waits for a token and counts the request, on the network thread and only for
     * requests that are really sent.
     */
    private class QuotaInterceptor(private val guard: QuotaGuard) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val providerId = request.header(HEADER_QUOTA_PROVIDER)
                ?: return chain.proceed(request)
            val policy = QuotaPolicy(
                requestsPerMinute = request.header(HEADER_QUOTA_MINUTE)?.toIntOrNull() ?: 0,
                requestsPerDay = request.header(HEADER_QUOTA_DAY)?.toIntOrNull() ?: 0,
            )
            runBlocking { guard.acquire(providerId, policy) }
            val stripped = request.newBuilder()
                .removeHeader(HEADER_QUOTA_PROVIDER)
                .removeHeader(HEADER_QUOTA_MINUTE)
                .removeHeader(HEADER_QUOTA_DAY)
                .build()
            return chain.proceed(stripped)
        }
    }

    private fun buildUrl(base: String, query: Map<String, String>): String? {
        val builder = base.toHttpUrlOrNull()?.newBuilder() ?: return null
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    companion object {
        private const val BASE_BACKOFF_MS = 800L
        private const val HEADER_QUOTA_PROVIDER = "X-FootballAi-Quota-Provider"
        private const val HEADER_QUOTA_MINUTE = "X-FootballAi-Quota-Minute"
        private const val HEADER_QUOTA_DAY = "X-FootballAi-Quota-Day"
        private val RETRYABLE = setOf(
            ErrorCode.RATE_LIMITED,
            ErrorCode.REQUEST_FAILED,
            ErrorCode.NETWORK_UNAVAILABLE,
        )

        /**
         * Documents the app downloads are CSV and JSON files that change once in a while.
         * Without a cache, every refresh downloaded all of them again — 7-8 seconds of
         * waiting for files that had not changed. With one, an unchanged document costs a
         * single conditional request and the body is read from disk.
         */
        fun defaultClient(cacheDir: File? = null): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .apply {
                if (cacheDir != null) {
                    cache(Cache(File(cacheDir, "http"), CACHE_BYTES))
                    // Hosts advertise long freshness (GitHub sends five minutes), which is
                    // right for a browser and wrong for a refresh button: it would answer
                    // from disk and never notice a published result. Revalidating after a
                    // minute keeps the refresh honest and still cheap.
                    networkInterceptors().add(shortFreshness)
                }
            }
            .build()

        /** Caps how long a downloaded document may be served without asking the server. */
        private val shortFreshness = Interceptor { chain: Interceptor.Chain ->
            val response: Response = chain.proceed(chain.request())
            val cacheControl = response.header("Cache-Control")
            if (cacheControl == null || !cacheControl.contains("max-age")) {
                response
            } else {
                response.newBuilder()
                    .header("Cache-Control", "max-age=$SHORT_FRESHNESS_SECONDS")
                    .build()
            }
        }

        private const val CACHE_BYTES = 32L * 1024 * 1024
        private const val SHORT_FRESHNESS_SECONDS = 60
    }
}
