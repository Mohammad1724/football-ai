package com.footballai.app

import com.footballai.app.domain.catalog.CompetitionOrder
import com.footballai.app.domain.model.Competition
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The manual competition order is a user decision, so it has to survive the cases the
 * data actually produces: a competition that disappears, one that appears later, and a
 * first move that has to pin everything that came before it.
 */
class CompetitionOrderTest {

    private fun competition(id: String, name: String) = Competition(
        id = id,
        name = name,
        country = null,
        emblemUrl = null,
        season = null,
        providerId = id.substringBefore(':'),
        hasFullCoverage = false,
    )

    private val competitions = listOf(
        competition("a:league:1", "Alpha League"),
        competition("a:league:2", "Beta League"),
        competition("a:league:3", "Gamma League"),
    )

    @Test
    fun `without an order competitions are alphabetical`() {
        val sorted = CompetitionOrder.sorted(competitions.reversed(), emptyList())
        assertEquals(listOf("Alpha League", "Beta League", "Gamma League"), sorted.map { it.name })
    }

    @Test
    fun `the user order wins and unknown competitions follow alphabetically`() {
        val sorted = CompetitionOrder.sorted(competitions, listOf("a:league:3", "a:league:1"))
        assertEquals(listOf("Gamma League", "Alpha League", "Beta League"), sorted.map { it.name })
    }

    @Test
    fun `a stored id that no longer exists is ignored`() {
        val sorted = CompetitionOrder.sorted(competitions, listOf("gone:league:9", "a:league:2"))
        assertEquals(listOf("Beta League", "Alpha League", "Gamma League"), sorted.map { it.name })
    }

    @Test
    fun `the first move pins the competitions before it`() {
        val ids = competitions.map { it.id }
        val moved = CompetitionOrder.move(ids, emptyList(), "a:league:3", up = true)
        assertEquals(listOf("a:league:1", "a:league:3", "a:league:2"), moved)
        assertEquals(ids.toSet(), moved.toSet())
    }

    @Test
    fun `moving past either end changes nothing`() {
        val ids = competitions.map { it.id }
        assertEquals(CompetitionOrder.sortedIds(ids, emptyList()), CompetitionOrder.move(ids, emptyList(), "a:league:1", up = true))
        assertEquals(
            CompetitionOrder.sortedIds(ids, emptyList()),
            CompetitionOrder.move(ids, emptyList(), "a:league:3", up = false),
        )
    }
}
