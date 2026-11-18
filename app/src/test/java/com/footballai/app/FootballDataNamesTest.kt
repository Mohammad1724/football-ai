package com.footballai.app

import com.footballai.app.domain.catalog.TeamNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The archive abbreviates and the rest of the world does not.
 *
 * "Ein Frankfurt", "Ath Madrid", "M'gladbach" and "Nott'm Forest" appear in no other
 * source, so without a written-out name a club from the archive can never reach its crest
 * or its Persian name. A name that is not in the dictionary stays unclaimed.
 */
class FootballDataNamesTest {

    @Test
    fun `an archive abbreviation is written out in full`() {
        assertEquals("Eintracht Frankfurt", TeamNames.canonicalName("Ein Frankfurt"))
        assertEquals("Atlético Madrid", TeamNames.canonicalName("Ath Madrid"))
        assertEquals("Borussia Mönchengladbach", TeamNames.canonicalName("M'gladbach"))
        assertEquals("Nottingham Forest", TeamNames.canonicalName("Nott'm Forest"))
        assertEquals("Paris Saint-Germain", TeamNames.canonicalName("Paris SG"))
        assertEquals("FC Köln", TeamNames.canonicalName("FC Koln"))
    }

    @Test
    fun `a published full name resolves as itself`() {
        assertEquals("Barcelona", TeamNames.canonicalName("Barcelona"))
        assertEquals("Liverpool", TeamNames.canonicalName("Liverpool"))
    }

    @Test
    fun `an unknown name is not attached to the nearest club`() {
        assertNull(TeamNames.canonicalName("Some Village FC"))
        assertNull(TeamNames.canonicalName(""))
    }

    @Test
    fun `case, accents and punctuation do not change the answer`() {
        assertEquals(
            TeamNames.canonicalName("Atletico Madrid"),
            TeamNames.canonicalName("atlético   madrid"),
        )
    }
}
