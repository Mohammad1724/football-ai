package com.footballai.app

import com.footballai.app.data.local.entity.TeamEntity
import com.footballai.app.data.local.mapper.toRef
import com.footballai.app.domain.catalog.CrestUrls
import com.footballai.engine.model.TeamKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A club badge comes from the source. When the stored row has no link, the source's own
 * published address for that club id is used, and nothing is invented for a team whose id
 * is not a number, whose kind is a nation, or whose provider is somebody else.
 */
class CrestUrlsTest {

    @Test
    fun `a numeric club id of the keyless source has a published address`() {
        assertEquals(
            "https://a.espncdn.com/i/teamlogos/soccer/500/132.png",
            CrestUrls.forClub("espn-open", "132", TeamKind.CLUB),
        )
    }

    @Test
    fun `a national team is never given a club crest`() {
        assertNull(CrestUrls.forClub("espn-open", "132", TeamKind.NATIONAL))
    }

    @Test
    fun `a key that is not a club id gets nothing`() {
        assertNull(CrestUrls.forClub("espn-open", "bayern-munich", TeamKind.CLUB))
        assertNull(CrestUrls.forClub("espn-open", "", TeamKind.CLUB))
    }

    @Test
    fun `another provider is never given this folder`() {
        assertNull(CrestUrls.forClub("football-data-org", "132", TeamKind.CLUB))
    }

    @Test
    fun `a stored crest wins over the fallback`() {
        val stored = team(crest = "https://example.com/own.png")
        assertEquals("https://example.com/own.png", stored.toRef().crestUrl)
    }

    @Test
    fun `a stored club without a crest falls back to the published address`() {
        assertEquals(
            "https://a.espncdn.com/i/teamlogos/soccer/500/132.png",
            team(crest = null).toRef().crestUrl,
        )
    }

    @Test
    fun `a stored nation without a crest keeps none`() {
        val nation = team(crest = null, kind = TeamKind.NATIONAL)
        assertNull(nation.toRef().crestUrl)
    }

    private fun team(crest: String?, kind: TeamKind = TeamKind.CLUB) = TeamEntity(
        id = "espn-open:team:132",
        providerId = "espn-open",
        providerKey = "132",
        name = "FC Bayern München",
        shortName = "FCB",
        crestUrl = crest,
        updatedAt = 0L,
        kind = kind.name,
    )
}
