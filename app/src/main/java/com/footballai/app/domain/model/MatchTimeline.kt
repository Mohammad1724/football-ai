package com.footballai.app.domain.model

/** Which side of the pitch an entry belongs to. */
enum class TimelineSide { HOME, AWAY, UNKNOWN }

/**
 * One line of a match timeline, already resolved to what the row can say.
 *
 * Kept apart from both the stored event and the composable so the ordering rules — the
 * half-time break, an assist that is really the scorer — can be tested without a screen.
 */
data class TimelineEntry(
    val minute: Int,
    val kind: Kind,
    val side: TimelineSide,
    val playerName: String? = null,
    val assistName: String? = null,
) {
    enum class Kind {
        GOAL,
        OWN_GOAL,
        PENALTY_GOAL,
        PENALTY_MISSED,
        YELLOW_CARD,
        RED_CARD,
        SECOND_YELLOW,
        SUBSTITUTION,
        VAR,
        OTHER,
        HALF_TIME,
    }

    val isHalfTime: Boolean get() = kind == Kind.HALF_TIME

    val isGoal: Boolean
        get() = kind == Kind.GOAL || kind == Kind.OWN_GOAL || kind == Kind.PENALTY_GOAL
}

/**
 * Turns stored events into the lines a reader scrolls through.
 *
 * Two rules matter here. The half-time break is placed where it happened — before the first
 * event after the 45th minute — and only for a match that has actually reached it; a match
 * whose stored events stop at minute 30 must not be drawn as if the break had passed. And an
 * assist that names the scorer himself is not an assist: some sources fill the field with the
 * scorer for a penalty, and "goal: X — assist: X" reads as a mistake.
 */
object MatchTimeline {

    private const val HALF_TIME_MINUTE = 45

    /**
     * @param includeHalfTime true once the first half is over (half-time or finished). A live
     *   match in the second half is not enough on its own: pass true when the minute passed 45.
     */
    fun build(
        events: List<MatchEvent>,
        homeTeamId: String,
        awayTeamId: String,
        includeHalfTime: Boolean,
    ): List<TimelineEntry> {
        val ordered = events.sortedBy { it.minute }
        val entries = ArrayList<TimelineEntry>(ordered.size + 1)
        var halfTimeInserted = false
        for (event in ordered) {
            if (includeHalfTime && !halfTimeInserted && event.minute > HALF_TIME_MINUTE) {
                entries += halfTimeEntry()
                halfTimeInserted = true
            }
            entries += event.toEntry(homeTeamId, awayTeamId)
        }
        if (includeHalfTime && !halfTimeInserted && entries.isNotEmpty()) {
            entries += halfTimeEntry()
        }
        return entries
    }

    private fun halfTimeEntry() = TimelineEntry(
        minute = HALF_TIME_MINUTE,
        kind = TimelineEntry.Kind.HALF_TIME,
        side = TimelineSide.UNKNOWN,
    )

    private fun MatchEvent.toEntry(homeTeamId: String, awayTeamId: String): TimelineEntry {
        val side = when (teamId) {
            null -> TimelineSide.UNKNOWN
            homeTeamId -> TimelineSide.HOME
            awayTeamId -> TimelineSide.AWAY
            else -> TimelineSide.UNKNOWN
        }
        val scorer = playerName?.trim()?.takeIf { it.isNotEmpty() }
        val assist = assistName?.trim()?.takeIf { it.isNotEmpty() && !it.equals(scorer, ignoreCase = true) }
        return TimelineEntry(
            minute = minute,
            kind = kindOf(type),
            side = side,
            playerName = scorer,
            assistName = assist,
        )
    }

    private fun kindOf(type: MatchEventType): TimelineEntry.Kind = when (type) {
        MatchEventType.GOAL -> TimelineEntry.Kind.GOAL
        MatchEventType.OWN_GOAL -> TimelineEntry.Kind.OWN_GOAL
        MatchEventType.PENALTY_GOAL -> TimelineEntry.Kind.PENALTY_GOAL
        MatchEventType.PENALTY_MISSED -> TimelineEntry.Kind.PENALTY_MISSED
        MatchEventType.YELLOW_CARD -> TimelineEntry.Kind.YELLOW_CARD
        MatchEventType.RED_CARD -> TimelineEntry.Kind.RED_CARD
        MatchEventType.SECOND_YELLOW -> TimelineEntry.Kind.SECOND_YELLOW
        MatchEventType.SUBSTITUTION -> TimelineEntry.Kind.SUBSTITUTION
        MatchEventType.VAR -> TimelineEntry.Kind.VAR
        // A stored event of a kind the timeline has no label for still happened; it is drawn
        // as a plain note rather than dropped.
        MatchEventType.OTHER -> TimelineEntry.Kind.OTHER
    }
}
