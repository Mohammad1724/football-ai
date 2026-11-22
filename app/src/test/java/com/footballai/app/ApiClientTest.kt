package com.footballai.app

import com.footballai.app.core.ErrorCode
import com.footballai.app.core.Outcome
import com.footballai.app.core.TimeProvider
import com.footballai.app.data.remote.ApiClient
import com.footballai.app.data.remote.QuotaGuard
import com.footballai.app.data.remote.QuotaStore
import com.footballai.app.domain.model.QuotaPolicy
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApiClientTest {

    private val guard = QuotaGuard(
        store = object : QuotaStore {
            override suspend fun readCounter(providerId: String): Pair<Long, Int> = 0L to 0
            override suspend fun writeCounter(providerId: String, dayEpochMs: Long, count: Int) = Unit
        },
        clock = TimeProvider.System,
    )

    @Test
    fun `dns failure remains a network error instead of request rejected`() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor(
            Interceptor { throw UnknownHostException("football-data.co.uk") },
        ).build()
        val client = ApiClient(guard, http, maxRetries = 0, sleeper = {})

        val result = client.get("csv", QuotaPolicy(0, 0), "https://football-data.co.uk/fixtures.csv")

        val failure = assertIs<Outcome.Failure>(result)
        assertEquals(ErrorCode.NETWORK_UNAVAILABLE, failure.error.code)
    }

    @Test
    fun `http failures preserve their response status for sync diagnostics`() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor(
            Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Unavailable")
                    .body("".toResponseBody())
                    .build()
            },
        ).build()
        val client = ApiClient(guard, http, maxRetries = 0, sleeper = {})

        val result = client.get("csv", QuotaPolicy(0, 0), "https://football-data.co.uk/fixtures.csv")

        val failure = assertIs<Outcome.Failure>(result)
        assertEquals(ErrorCode.REQUEST_FAILED, failure.error.code)
        assertEquals(503, failure.error.httpStatus)
    }
}
