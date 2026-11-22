package com.footballai.app.domain.catalog

import com.footballai.app.domain.model.Competition

/**
 * The order the user arranged competitions in.
 *
 * The stored list is only a set of preferences: a competition the user never moved keeps
 * the default order, a competition that has since disappeared from the sources is simply
 * ignored, and a new one appears at its alphabetical place instead of at a random index.
 * Nothing here is derived from kickoff times — the point is that the user's order beats
 * the app's guess.
 */
object CompetitionOrder {

    /** Explicit rank of every id the user ordered; anything else sorts after it. */
    private fun explicitRanks(order: List<String>): Map<String, Int> =
        order.withIndex().associate { (index, id) -> id to index }

    /** Ids in display order: the user's list first, then everything else by id. */
    fun sortedIds(ids: List<String>, order: List<String>): List<String> {
        val ranks = explicitRanks(order)
        return ids.sortedWith(compareBy({ ranks[it] ?: Int.MAX_VALUE }, { it }))
    }

    /** Competitions in display order, falling back to the published name. */
    fun sorted(competitions: List<Competition>, order: List<String>): List<Competition> {
        val ranks = explicitRanks(order)
        return competitions.sortedWith(compareBy({ ranks[it.id] ?: Int.MAX_VALUE }, { it.name }))
    }

    /** Rank of each competition, for sorting matches by the competition they belong to. */
    fun ranks(competitions: List<Competition>, order: List<String>): Map<String, Int> {
        val ranks = explicitRanks(order)
        return competitions
            .sortedWith(compareBy({ ranks[it.id] ?: Int.MAX_VALUE }, { it.name }))
            .mapIndexed { index, competition -> competition.id to index }
            .toMap()
    }

    /**
     * Moves [id] one place up or down and returns the full order, so the very first move
     * also pins every competition that came before it.
     */
    fun move(allIds: List<String>, order: List<String>, id: String, up: Boolean): List<String> {
        val current = sortedIds(allIds, order).toMutableList()
        val from = current.indexOf(id)
        if (from < 0) return current
        val to = if (up) from - 1 else from + 1
        if (to !in current.indices) return current
        current.add(to, current.removeAt(from))
        return current
    }
}
