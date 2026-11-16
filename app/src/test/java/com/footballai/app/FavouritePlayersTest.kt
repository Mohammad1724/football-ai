package com.footballai.app

import com.footballai.app.domain.catalog.NameLocalization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Following a player is keyed by the club plus the normalised name.
 *
 * These tests pin the three things that key has to get right: spelling must not matter, the
 * club must matter (two players can share a name), and what is stored must still be readable
 * in the settings list.
 */
class FavouritePlayersTest {

    @Test
    fun `spellings of one player share a key`() {
        assertEquals(
            NameLocalization.playerFavouriteKey("team-1", "Michael Olise"),
            NameLocalization.playerFavouriteKey("team-1", "  michael   OLISE "),
        )
        assertEquals(
            NameLocalization.playerFavouriteKey("team-66", "N'Golo Kanté"),
            NameLocalization.playerFavouriteKey("team-66", "Ngolo Kante"),
        )
    }

    @Test
    fun `the same name under two clubs is two players`() {
        assertNotEquals(
            NameLocalization.playerFavouriteKey("team-1", "J. Silva"),
            NameLocalization.playerFavouriteKey("team-2", "J. Silva"),
        )
    }

    @Test
    fun `the stored entry keeps the name readable`() {
        val entry = NameLocalization.playerEntry("team-1", "Michael Olise")
        assertEquals("Michael Olise", NameLocalization.playerNameOf(entry))
        assertEquals(
            NameLocalization.playerFavouriteKey("team-1", "Michael Olise"),
            NameLocalization.playerFavouriteKey("team-1", NameLocalization.playerNameOf(entry)),
        )
    }

    @Test
    fun `a name with a pipe in it still reads back`() {
        // Sources are not supposed to send this, but a stored entry must never crash a list.
        val entry = NameLocalization.playerEntry("team-1", "A|B")
        assertEquals("B", NameLocalization.playerNameOf(entry))
    }
}
