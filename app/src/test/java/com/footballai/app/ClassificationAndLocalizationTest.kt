package com.footballai.app

import com.footballai.app.domain.catalog.ClassificationSource
import com.footballai.app.domain.catalog.CompetitionCatalog
import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.engine.model.CompetitionType
import com.footballai.engine.model.Gender
import com.footballai.engine.model.TeamKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The classification catalogue is the only place that decides whether a competition
 * is a club league, a knockout tie or an international fixture, and whether its teams
 * are clubs or national teams. Getting this wrong silently changes the model, the
 * calibration sample and the confidence, so it is tested directly.
 */
class ClassificationAndLocalizationTest {

    private fun fdo(key: String, name: String) =
        CompetitionCatalog.classify("football-data-org", key, name)

    private fun dynamic(name: String) =
        CompetitionCatalog.classify("api-football", "999", name, "World")

    @Test
    fun `catalogue keys win over name heuristics`() {
        val premierLeague = fdo("PL", "Premier League")
        assertEquals(CompetitionType.CLUB_LEAGUE, premierLeague.type)
        assertEquals(TeamKind.CLUB, premierLeague.teamKind)
        assertEquals(ClassificationSource.CATALOG, premierLeague.source)

        val championsLeague = fdo("CL", "UEFA Champions League")
        assertEquals(CompetitionType.CLUB_KNOCKOUT, championsLeague.type)

        val worldCup = fdo("WC", "FIFA World Cup")
        assertEquals(CompetitionType.INTERNATIONAL_TOURNAMENT, worldCup.type)
        assertEquals(TeamKind.NATIONAL, worldCup.teamKind)
        assertNotNull(worldCup.persianName)
    }

    @Test
    fun `a club-league-only provider feed is a provider default, not a guess`() {
        val csv = CompetitionCatalog.classify("football-data-co-uk", "E0", "Premier League", "England")
        assertEquals(CompetitionType.CLUB_LEAGUE, csv.type)
        assertEquals(Gender.MEN, csv.gender)
        assertEquals(ClassificationSource.PROVIDER_DEFAULT, csv.source)
        assertTrue(csv.isKnown)

        val openMatchday = CompetitionCatalog.classify("open-matchday", "some-new-league", "Some New League")
        assertEquals(ClassificationSource.PROVIDER_DEFAULT, openMatchday.source)
    }

    @Test
    fun `a provider key means only what its own provider publishes`() {
        // football-data.org: EC = European Championship. football-data.co.uk: EC = the
        // English fifth tier. Scoping keys by provider is what keeps them apart.
        assertEquals(CompetitionType.INTERNATIONAL_TOURNAMENT, fdo("EC", "European Championship").type)
        val csvNationalLeague = CompetitionCatalog.classify("football-data-co-uk", "EC", "National League", "England")
        assertEquals(CompetitionType.CLUB_LEAGUE, csvNationalLeague.type)
        assertEquals(TeamKind.CLUB, csvNationalLeague.teamKind)
    }

    @Test
    fun `dynamically published competitions are classified by explicit name rules`() {
        assertEquals(CompetitionType.INTERNATIONAL_TOURNAMENT, dynamic("AFC Asian Cup").type)
        assertEquals(CompetitionType.INTERNATIONAL_QUALIFIER, dynamic("World Cup - Qualification Asia").type)
        assertEquals(CompetitionType.INTERNATIONAL_FRIENDLY, dynamic("Friendlies").type)
        assertEquals(CompetitionType.CLUB_KNOCKOUT, dynamic("Copa Libertadores").type)
        assertEquals(CompetitionType.WOMEN_INTERNATIONAL, dynamic("Women's World Cup").type)
        assertEquals(CompetitionType.WOMEN_CLUB_LEAGUE, dynamic("Women's Super League").type)
        assertEquals(CompetitionType.CLUB_LEAGUE, dynamic("Persian Gulf Pro League").type)
    }

    @Test
    fun `a women's competition is never classified from a men's entry`() {
        // "World Cup" is a men's tournament in the catalogue; the women's edition must
        // not inherit it just because the name contains the same words.
        assertEquals(CompetitionType.WOMEN_INTERNATIONAL, dynamic("Women's World Cup 2027").type)
        assertEquals(CompetitionType.WOMEN_INTERNATIONAL, dynamic("Women's Copa America").type)
        assertEquals(CompetitionType.WOMEN_CLUB_LEAGUE, dynamic("Women's Super League").type)
        assertEquals(CompetitionType.WOMEN_CLUB_LEAGUE, dynamic("Frauen Bundesliga").type)
    }

    @Test
    fun `an unknown competition is reported as unknown instead of being treated as a league`() {
        val unknown = dynamic("Kategoria Superiore")
        assertFalse(unknown.isKnown)
        assertEquals(ClassificationSource.UNKNOWN, unknown.source)
    }

    @Test
    fun `profile carries type, gender and neutral venue into the engine`() {
        val profile = fdo("WC", "FIFA World Cup").profile(neutralVenue = true)
        assertEquals(CompetitionType.INTERNATIONAL_TOURNAMENT, profile.type)
        assertTrue(profile.isInternational)
        assertTrue(profile.neutralVenue)
    }

    @Test
    fun `persian names and flags are never invented`() {
        assertEquals("ایران", NameLocalization.persianTeamName("Iran"))
        assertEquals("بارسلونا", NameLocalization.persianTeamName("FC Barcelona"))
        assertNull(NameLocalization.persianTeamName("Some Village FC"))
        assertNotNull(NameLocalization.flagEmoji("Japan"))
        assertNull(NameLocalization.flagEmoji("Some Village FC"))
    }

    @Test
    fun `search matches english, short and persian names`() {
        assertTrue(NameLocalization.matches("real", "Real Madrid"))
        assertTrue(NameLocalization.matches("ایران", "Iran"))
        assertTrue(NameLocalization.matches("پرسپولیس", "Persepolis"))
        assertFalse(NameLocalization.matches("zzz", "Persepolis"))
    }

    @Test
    fun `national teams are recognised by name only when the catalogue knows them`() {
        assertTrue(NameLocalization.isNationalTeamName("Morocco"))
        assertFalse(NameLocalization.isNationalTeamName("Persepolis"))
    }

    @Test
    fun `display name falls back to the provider name`() {
        assertEquals("ایران", NameLocalization.displayName("Iran", "fa"))
        assertEquals("Iran", NameLocalization.displayName("Iran", "en"))
        assertEquals("Unlisted FC", NameLocalization.displayName("Unlisted FC", "fa"))
    }

    @Test
    fun `provider keys are matched exactly and an unknown key does not create a classification`() {
        // Keys are published identifiers, not free text; a key the catalogue does not
        // own must not be treated as one of the known competitions.
        assertEquals(ClassificationSource.UNKNOWN, fdo("pl", "Some Unlisted League").source)
        assertEquals(ClassificationSource.CATALOG, fdo("PL", "Some Unlisted League").source)
    }
}
