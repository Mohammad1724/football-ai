package com.footballai.app

import com.footballai.app.core.TimeProvider
import com.footballai.app.data.remote.ApiClient
import com.footballai.app.data.remote.EspnOpenProvider
import com.footballai.app.data.remote.QuotaGuard
import com.footballai.app.data.remote.QuotaStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A badge address recovered by name has to be the same club.
 *
 * Searching "Bayern Munich" also returns "Bayern Munich II": a reserve team's crest on the
 * first team would be a worse defect than the initials it replaced, so only an exact
 * normalised name match is accepted, and a search that confirms nothing returns nothing.
 */
class EspnCrestSearchTest {

    private val provider = EspnOpenProvider(
        client = ApiClient(
            QuotaGuard(
                store = object : QuotaStore {
                    override suspend fun readCounter(providerId: String): Pair<Long, Int> = 0L to 0
                    override suspend fun writeCounter(
                        providerId: String,
                        dayEpochMs: Long,
                        count: Int,
                    ) = Unit
                },
                clock = TimeProvider.System,
            ),
            maxRetries = 0,
            sleeper = {},
        ),
        clock = object : TimeProvider {
            override fun nowEpochMs(): Long = 1_794_614_400_000L
        },
    )

    private val payload = """
        {"items":[
          {"id":"21630","name":"Bayern Munich II","displayName":"Bayern München II","location":"Bayern Munich II",
           "logos":[{"href":"https://a.espncdn.com/i/teamlogos/soccer/500/21630.png","rel":["full","default"]}]},
          {"id":"132","name":"Bayern Munich","displayName":"Bayern München","location":"Bayern Munich",
           "logos":[{"href":"https://a.espncdn.com/i/teamlogos/soccer/500-dark/132.png","rel":["full","dark"]},
                    {"href":"https://a.espncdn.com/i/teamlogos/soccer/500/132.png","rel":["full","default"]}]}
        ]}
    """.trimIndent()

    @Test
    fun `the exact club wins, and the default crest is the one stored`() {
        assertEquals(
            "https://a.espncdn.com/i/teamlogos/soccer/500/132.png",
            provider.parseCrestSearch(payload, "Bayern Munich"),
        )
    }

    @Test
    fun `diacritics and punctuation are not part of the match`() {
        assertEquals(
            "https://a.espncdn.com/i/teamlogos/soccer/500/132.png",
            provider.parseCrestSearch(payload, "bayern munchen"),
        )
    }

    @Test
    fun `an unconfirmed name gets no address rather than the nearest club`() {
        assertNull(provider.parseCrestSearch(payload, "Ein Frankfurt"))
    }

    @Test
    fun `a document that is not a search answer is not a match`() {
        assertNull(provider.parseCrestSearch("{\"items\":\"nope\"}", "Bayern Munich"))
    }
}
