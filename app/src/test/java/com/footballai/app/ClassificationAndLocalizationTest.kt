package com.footballai.app

import com.footballai.app.domain.catalog.ClassificationSource
import com.footballai.app.domain.catalog.CompetitionCatalog
import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.domain.catalog.TeamNames
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
    fun `every team in the dictionary can be named and looked up`() {
        // The dictionary is the promise that no screen shows an unnamed team. A row
        // without a Persian name, or one that cannot find itself, breaks that promise.
        val unnamed = TeamNames.nationalTeams.filter { it.persian.isBlank() }
        assertTrue(unnamed.isEmpty(), "national teams without a Persian name: ${unnamed.map { it.english }}")
        val unresolvable = TeamNames.clubs.filter { NameLocalization.persianTeamName(it.english) == null }
        assertTrue(unresolvable.isEmpty(), "clubs that cannot find themselves: ${unresolvable.map { it.english }}")
        assertTrue(TeamNames.nationalTeams.size >= 320)
        assertTrue(TeamNames.clubs.size >= 250)
    }

    @Test
    fun `the spelling a source uses resolves to the same team`() {
        assertEquals("ایران", NameLocalization.persianTeamName("IR Iran"))
        assertEquals("کره جنوبی", NameLocalization.persianTeamName("Korea Republic"))
        assertEquals("ساحل عاج", NameLocalization.persianTeamName("Côte d'Ivoire"))
        assertEquals("بوسنی و هرزگوین", NameLocalization.persianTeamName("Bosnia-Herzegovina"))
        assertEquals("منچستریونایتد", NameLocalization.persianTeamName("Man United"))
        assertEquals("یونیون برلین", NameLocalization.persianTeamName("1. FC Union Berlin"))
        assertEquals("بارسلونا", NameLocalization.persianTeamName("Barcelona CF"))
    }

    @Test
    fun `a suffixed name stays the same nation and says what the suffix means`() {
        assertEquals("اسپانیا (بانوان)", NameLocalization.persianTeamName("Spain Women"))
        assertEquals("ژاپن (زیر ۲۱ سال)", NameLocalization.persianTeamName("Japan U21"))
        // A suffix must not turn an unknown name into a known one.
        assertNull(NameLocalization.persianTeamName("Nowhere Women"))
    }

    @Test
    fun `both names are offered for the ui and the unknown one is not invented`() {
        assertEquals("ایران" to "Iran", NameLocalization.namePair("Iran", "fa"))
        assertEquals("Iran" to "ایران", NameLocalization.namePair("Iran", "en"))
        assertEquals("Unlisted FC" to null, NameLocalization.namePair("Unlisted FC", "fa"))
    }

    @Test
    fun `a flag is only offered for a team that has one`() {
        assertNotNull(NameLocalization.flagEmoji("England"))
        assertNotNull(NameLocalization.flagEmoji("Curaçao"))
        assertEquals(7, NameLocalization.flagEmoji("England")!!.codePointCount(0, NameLocalization.flagEmoji("England")!!.length))
        assertNull(NameLocalization.flagEmoji("Tibet"))
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

    private fun espn(slug: String, name: String) =
        CompetitionCatalog.classify("espn-open", slug, name)

    @Test
    fun `espn slugs are catalogued with a gender the name could not decide`() {
        val nations = espn("uefa.nations", "UEFA Nations League")
        assertEquals(CompetitionType.INTERNATIONAL_TOURNAMENT, nations.type)
        assertEquals(Gender.MEN, nations.gender)
        assertEquals(TeamKind.NATIONAL, nations.teamKind)
        assertEquals(ClassificationSource.CATALOG, nations.source)
        assertEquals("لیگ ملت‌های اروپا", nations.persianName)

        val womensNations = espn("uefa.w.nations", "UEFA Women's Nations League")
        assertEquals(CompetitionType.WOMEN_INTERNATIONAL, womensNations.type)
        assertEquals(Gender.WOMEN, womensNations.gender)
        assertEquals(TeamKind.NATIONAL, womensNations.teamKind)

        val womensSuperLeague = espn("eng.w.1", "Women's Super League")
        assertEquals(CompetitionType.WOMEN_CLUB_LEAGUE, womensSuperLeague.type)
        assertEquals(Gender.WOMEN, womensSuperLeague.gender)
        assertEquals(TeamKind.CLUB, womensSuperLeague.teamKind)

        assertEquals(CompetitionType.INTERNATIONAL_QUALIFIER, espn("fifa.worldq.uefa", "World Cup Qualifiers (UEFA)").type)
        assertEquals(CompetitionType.WOMEN_CLUB_LEAGUE, espn("uefa.wchampions", "UEFA Women's Champions League").type)
    }

    @Test
    fun `an espn slug decides gender even when the name says nothing`() {
        // ESPN publishes the women's league under a slug, and a provider may well publish
        // a neutral name; the slug is the fact, the name is not.
        assertEquals(Gender.WOMEN, espn("eng.w.1", "Super League").gender)
        // The same name under a men's slug must not inherit the women's entry.
        assertEquals(Gender.MEN, espn("eng.1", "Super League").gender)
    }

    @Test
    fun `an uncatalogued espn slug is not presented as a known competition`() {
        assertFalse(espn("xx.99", "Some Regional Cup").source == ClassificationSource.CATALOG)
    }
}
