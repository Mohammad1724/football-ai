package com.footballai.app.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Season helpers shared by the history importer and the onboarding flow.
 *
 * European league seasons are named after the year they start in, and a season that
 * starts in July/August is still "current" until the following summer. Computing this
 * from the UTC clock (instead of the device zone) keeps the requested file names stable
 * for users in Tehran, Berlin or anywhere else.
 */
object Seasons {

    /** Month from which a new season is considered to have started. */
    const val SEASON_START_MONTH = 7

    const val MIN_SEASONS = 1
    const val MAX_SEASONS = 10

    /** Start years of the most recent [count] seasons, newest first. */
    fun recentStartYears(now: ZonedDateTime, count: Int): List<Int> {
        val clamped = count.coerceIn(MIN_SEASONS, MAX_SEASONS)
        val currentStart = if (now.monthValue >= SEASON_START_MONTH) now.year else now.year - 1
        return (0 until clamped).map { currentStart - it }
    }

    fun recentStartYears(nowEpochMs: Long, count: Int, zone: ZoneOffset = ZoneOffset.UTC): List<Int> =
        recentStartYears(Instant.ofEpochMilli(nowEpochMs).atZone(zone), count)
}
