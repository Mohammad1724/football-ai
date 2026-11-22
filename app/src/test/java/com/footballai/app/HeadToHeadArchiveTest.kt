package com.footballai.app

import com.footballai.app.data.repository.sameArchiveDay
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeadToHeadArchiveTest {
    @Test fun `a synthetic archive kickoff on the match day is not counted as a previous meeting`() {
        val opened = Instant.parse("2026-11-20T20:00:00Z").toEpochMilli()
        assertTrue(sameArchiveDay(Instant.parse("2026-11-20T12:00:00Z").toEpochMilli(), opened))
        assertFalse(sameArchiveDay(Instant.parse("2026-11-19T12:00:00Z").toEpochMilli(), opened))
    }
}
