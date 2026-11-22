package com.footballai.app

import com.footballai.app.data.remote.TeamAliases
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The alias table is data, so the tests that matter are about its shape, not its luck:
 * every pair must be usable as a join key, and no pair may rewrite a key the live feed
 * still uses (that would attach this season's own results to the wrong club).
 */
class TeamAliasesTest {

    @Test fun `archive names resolve to the live feed's id`() {
        // Bundesliga: the archive says "Bayern Munich", the live feed says "FC Bayern München".
        assertEquals("fc-bayern-m-nchen", TeamAliases.canonical("D1", "bayern-munich"))
        assertEquals("1-fc-k-ln", TeamAliases.canonical("D1", "fc-koln"))
        // La Liga and Ligue 1 are the same story with a different publisher's shorthand.
        assertEquals("atl-tico-de-madrid", TeamAliases.canonical("SP1", "ath-madrid"))
        assertEquals("paris-saint-germain", TeamAliases.canonical("F1", "paris-sg"))
        // The Premier League has fewer gaps because both publishers mostly agree.
        assertEquals("man-utd", TeamAliases.canonical("E0", "man-united"))
        assertEquals("spurs", TeamAliases.canonical("E0", "tottenham"))
        // A league whose names already agree must not be "translated".
        assertNull(TeamAliases.canonical("E0", "arsenal"))
        assertNull(TeamAliases.canonical("SP1", "real-madrid"))
    }

    @Test fun `a league only ever resolves names it published`() {
        // The table is league-scoped: the same string in another league is not the same club.
        assertNull(TeamAliases.canonical("SP1", "man-united"))
        assertNull(TeamAliases.canonical("ZZ9", "bayern-munich"))
        assertNull(TeamAliases.canonical("", "bayern-munich"))
    }

    @Test fun `no pair rewrites a key that the live feed uses`() {
        for ((league, pairs) in TeamAliases.snapshot()) {
            for (live in pairs.values) {
                assertNull(
                    pairs[live],
                    "$league maps $live onward, which would rewrite a current team id",
                )
            }
        }
    }

    @Test fun `every line is well formed and no source has two targets`() {
        val snapshot = TeamAliases.snapshot()
        assertTrue(snapshot.isNotEmpty(), "the table must not be empty")
        assertTrue(TeamAliases.pairCount > 150, "the table shrank unexpectedly: ${TeamAliases.pairCount}")
        for ((league, pairs) in snapshot) {
            assertTrue(league.isNotBlank() && league == league.trim())
            for ((source, target) in pairs) {
                assertTrue(source.isNotBlank(), "blank source in $league")
                assertTrue(target.isNotBlank(), "blank target in $league")
                assertTrue(source != target, "$league maps $source onto itself")
                assertTrue(!source.contains('|') && !source.contains('='))
                assertTrue(!target.contains('|') && !target.contains('='))
            }
        }
    }

    @Test fun `the reverse index lists the archived names of a club`() {
        val archived = TeamAliases.archivedKeys("spurs")
        assertTrue("tottenham" in archived, "Tottenham must be reachable from the live id")
        assertTrue("spurs" !in archived, "a live id is not its own archived alias")
        assertTrue(TeamAliases.archivedKeys("no-such-club").isEmpty())
    }
}
