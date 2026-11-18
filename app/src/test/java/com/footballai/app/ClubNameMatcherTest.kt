package com.footballai.app

import com.footballai.app.data.remote.ClubNameMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two publishers naming one club, and the places where that has to stop.
 *
 * Every pair below is a real one from a live feed, an archive or a crest provider. The rule
 * set is deliberately narrow: a badge on the wrong club is worse than two initials, so each
 * accepted pair has to be explainable from the two strings alone.
 */
class ClubNameMatcherTest {

    @Test
    fun `legal form and founding year are not part of a club's name`() {
        assertTrue(ClubNameMatcher.matches("KAA Gent", "Gent"))
        assertTrue(ClubNameMatcher.matches("Cercle Brugge KSV", "Cercle Brugge"))
        assertTrue(ClubNameMatcher.matches("CD Nacional", "Nacional"))
        assertTrue(ClubNameMatcher.matches("1. FC Köln", "FC Köln"))
        assertTrue(ClubNameMatcher.matches("Bayer 04 Leverkusen", "Bayer Leverkusen"))
        assertTrue(ClubNameMatcher.matches("Sporting CP", "Sporting"))
        assertTrue(ClubNameMatcher.matches("RSC Anderlecht", "Anderlecht"))
    }

    @Test
    fun `a single word matches only the head of a longer name`() {
        // English, Dutch and German names put the club at the end.
        assertTrue(ClubNameMatcher.matches("Royal Antwerp FC", "Antwerp"))
        assertTrue(ClubNameMatcher.matches("KV Kortrijk", "Kortrijk"))
        // Spanish and Portuguese names put the family first, so one word is not evidence.
        assertFalse(ClubNameMatcher.matches("Real Madrid", "Real"))
        assertFalse(ClubNameMatcher.matches("Bayer Leverkusen", "Bayer"))
        assertFalse(ClubNameMatcher.matches("Académico de Viseu", "Academico"))
        assertFalse(ClubNameMatcher.matches("Eintracht Frankfurt", "Ein Frankfurt"))
    }

    @Test
    fun `two longer names are accepted when one contains the other's words`() {
        assertTrue(ClubNameMatcher.matches("Cercle Brugge", "Cercle Brugge KSV"))
        assertTrue(ClubNameMatcher.matches("AZ Alkmaar", "AZ Alkmaar Zaanstreek"))
    }

    @Test
    fun `a reserve, age or women's side is never the first team`() {
        assertFalse(ClubNameMatcher.matches("Bayern Munich II", "Bayern Munich"))
        assertFalse(ClubNameMatcher.matches("Bayern Munich", "Bayern Munich II"))
        assertFalse(ClubNameMatcher.matches("Valencia B", "Valencia"))
        assertFalse(ClubNameMatcher.matches("Manchester United U21", "Manchester United"))
        assertFalse(ClubNameMatcher.matches("Arsenal Women", "Arsenal"))
        // The same marker on both sides is the same team.
        assertTrue(ClubNameMatcher.matches("Manchester United U21", "Manchester United FC U21"))
    }

    @Test
    fun `names that only share a word are not the same club`() {
        assertFalse(ClubNameMatcher.matches("Manchester City", "Manchester United"))
        assertFalse(ClubNameMatcher.matches("Milan", "Inter"))
        assertFalse(ClubNameMatcher.matches("Sporting Kansas City", "Sporting Lisbon"))
        // "Club" is a distinctive word: Brugge has two clubs.
        assertFalse(ClubNameMatcher.matches("Club Brugge", "Cercle Brugge"))
    }

    @Test
    fun `one candidate is a match and two candidates are an ambiguity`() {
        val candidates = listOf("Gent", "Club Brugge", "Cercle Brugge KSV")
        assertEquals(
            "Cercle Brugge KSV",
            ClubNameMatcher.pick(candidates, { listOf(it) }, listOf("Cercle Brugge")),
        )
        // "Sporting" is the head of Sporting CP, not of Sporting Kansas City, so it is
        // one club and not an ambiguity.
        assertEquals(
            "Sporting CP",
            ClubNameMatcher.pick(
                listOf("Sporting CP", "Sporting Kansas City"),
                { listOf(it) },
                listOf("Sporting"),
            ),
        )
        // A first team and its reserve side in the same answer is an ambiguity.
        assertNull(
            ClubNameMatcher.pick(
                listOf("Real Madrid", "Real Madrid Castilla"),
                { listOf(it) },
                listOf("Real Madrid"),
            ),
        )
        assertNull(ClubNameMatcher.pick(candidates, { listOf(it) }, listOf("Anderlecht")))
    }

    @Test
    fun `a provider that publishes two names is judged on both`() {
        assertEquals(
            "Anderlecht",
            ClubNameMatcher.pick(
                listOf("Anderlecht"),
                { listOf("RSC Anderlecht", "Anderlecht") },
                listOf("Anderlecht"),
            ),
        )
    }
}
