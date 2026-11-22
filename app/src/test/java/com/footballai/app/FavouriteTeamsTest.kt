package com.footballai.app

import com.footballai.app.domain.catalog.NameLocalization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Following a team must survive the way sources spell it. The key is the contract: the
 * same team always produces the same key, a different team never does.
 */
class FavouriteTeamsTest {

    @Test
    fun `spellings of one team share a key`() {
        assertEquals(
            NameLocalization.favouriteKey("Manchester United"),
            NameLocalization.favouriteKey("Man United"),
        )
        assertEquals(
            NameLocalization.favouriteKey("Iran"),
            NameLocalization.favouriteKey("IR Iran"),
        )
        assertEquals(
            NameLocalization.favouriteKey("Bayern München"),
            NameLocalization.favouriteKey("Bayern Munich"),
        )
    }

    @Test
    fun `different teams keep different keys`() {
        assertNotEquals(
            NameLocalization.favouriteKey("Arsenal"),
            NameLocalization.favouriteKey("Chelsea"),
        )
        // Two clubs from one city are still two clubs.
        assertNotEquals(
            NameLocalization.favouriteKey("Manchester United"),
            NameLocalization.favouriteKey("Manchester City"),
        )
    }

    @Test
    fun `a team outside the dictionary still gets a stable key`() {
        assertEquals(
            NameLocalization.favouriteKey("Some Village FC"),
            NameLocalization.favouriteKey("  some   village fc "),
        )
    }

    @Test
    fun `clubs and nations are not confused with each other`() {
        assertNotEquals(
            NameLocalization.favouriteKey("Brazil"),
            NameLocalization.favouriteKey("Brazil Women"),
        )
    }
}
