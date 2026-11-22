package com.footballai.app

import com.footballai.app.core.*
import com.footballai.app.data.remote.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ApiFootballDiagnosticsTest {
    private val active = """{"errors":[],"response":{"subscription":{"active":true,"plan":"Free"},"requests":{"current":1,"limit_day":100}}}"""

    @Test fun `logical errors are classified without leaking their body`() {
        val cases = listOf(
            "token" to ("Invalid API key SECRET" to ErrorCode.INVALID_API_KEY),
            "requests" to ("Daily request limit reached" to ErrorCode.QUOTA_EXHAUSTED),
            "rateLimit" to ("Too many requests per minute" to ErrorCode.RATE_LIMITED),
            "plan" to ("Free plans do not have access to this season" to ErrorCode.PLAN_RESTRICTED),
            "unknown" to ("Something went wrong SECRET" to ErrorCode.REQUEST_FAILED),
        )
        for ((field, value) in cases) {
            val error = requireNotNull(ApiFootballDiagnostics.error("""{"errors":{"$field":"${value.first}"}}"""))
            assertEquals(value.second, error.code)
            assertNull(error.detail)
            assertFalse(error.toString().contains("SECRET"))
        }
    }

    @Test fun `status requires a valid active subscription and quota`() {
        assertNull(ApiFootballDiagnostics.accountError(active))
        assertEquals(ErrorCode.SUBSCRIPTION_INACTIVE, ApiFootballDiagnostics.accountError(active.replace("true", "false"))?.code)
        assertEquals(ErrorCode.QUOTA_EXHAUSTED, ApiFootballDiagnostics.accountError(active.replace("\"current\":1", "\"current\":100"))?.code)
        for (body in listOf("not json", "{}", "{\"response\":[]}", "{\"response\":{}}")) {
            assertEquals(ErrorCode.PARSE_ERROR, ApiFootballDiagnostics.accountError(body)?.code)
        }
    }

    private fun provider(server: MockWebServer): ApiFootballProvider {
        val clock = object : TimeProvider { override fun nowEpochMs() = 1_800_000_000_000L }
        val store = object : QuotaStore {
            override suspend fun readCounter(providerId: String) = 0L to 0
            override suspend fun writeCounter(providerId: String, dayEpochMs: Long, count: Int) = Unit
        }
        return ApiFootballProvider(ApiClient(QuotaGuard(store, clock), maxRetries = 0),
            apiKeyProvider = { "test-only-key" }, baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @Test fun `competition verification calls status with the direct API Sports header`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(active))
            assertIs<Outcome.Success<*>>(provider(server).competitions())
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/status", request.path)
            assertEquals("test-only-key", request.getHeader("x-apisports-key"))
        }
    }

    @Test fun `a rejected status cannot verify a key through the curated league list`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("test-only-key"))
            val result = assertIs<Outcome.Failure>(provider(server).competitions())
            assertEquals(ErrorCode.INVALID_API_KEY, result.error.code)
            assertNull(result.error.detail)
        }
    }

    @Test fun `forbidden is not automatically reported as an invalid key`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            assertEquals(ErrorCode.ACCESS_DENIED, assertIs<Outcome.Failure>(provider(server).competitions()).error.code)
        }
    }

    @Test fun `fixture plan restrictions survive a successful HTTP response`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"errors":{"plan":"Free plans do not have access to this season"},"response":[]}"""))
            val result = assertIs<Outcome.Failure>(provider(server).matches("39", 2026, null, null))
            assertEquals(ErrorCode.PLAN_RESTRICTED, result.error.code)
        }
    }
}
