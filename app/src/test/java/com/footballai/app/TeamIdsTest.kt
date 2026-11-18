package com.footballai.app

import com.footballai.app.data.remote.TeamIds
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Rewriting a stored id is the step that makes the archive visible to the rating book,
 * so it has to be boring and exact: the archive id changes, everything else stays the
 * same string it was.
 */
class TeamIdsTest {

    @Test fun `an archived club id becomes the id the live feed uses`() {
        assertEquals(
            "football-data-co-uk:team:fc-bayern-m-nchen",
            TeamIds.canonical("D1", "football-data-co-uk:team:bayern-munich"),
        )
        assertEquals(
            "football-data-co-uk:team:man-utd",
            TeamIds.canonical("E0", "football-data-co-uk:team:man-united"),
        )
    }

    @Test fun `an id that is already the live id is returned unchanged`() {
        val live = "football-data-co-uk:team:fc-bayern-m-nchen"
        assertEquals(live, TeamIds.canonical("D1", live))
        val unrelated = "football-data-co-uk:team:arsenal"
        assertEquals(unrelated, TeamIds.canonical("E0", unrelated))
    }

    @Test fun `nothing is rewritten without a league or a known name`() {
        assertEquals(
            "football-data-co-uk:team:bayern-munich",
            TeamIds.canonical("", "football-data-co-uk:team:bayern-munich"),
        )
        assertEquals(
            "football-data-co-uk:team:bayern-munich",
            TeamIds.canonical("ZZ9", "football-data-co-uk:team:bayern-munich"),
        )
        // A league is not a licence to rename a club from a different one.
        assertEquals(
            "football-data-co-uk:team:man-united",
            TeamIds.canonical("D1", "football-data-co-uk:team:man-united"),
        )
    }

    @Test fun `the league key is read from the competition id`() {
        assertEquals("D1", TeamIds.leagueOf("football-data-co-uk:league:D1"))
        assertEquals("E0", TeamIds.leagueOf("football-data-co-uk:league:E0"))
        // A malformed id must not crash the prediction path.
        assertEquals("", TeamIds.leagueOf(""))
    }
}
