package com.footballai.app

import com.footballai.app.core.Formatters
import com.footballai.app.core.RelativeUnit
import com.footballai.app.core.TimeConstants
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormattersTest {

    @Test
    fun `percentages use latin digits in english`() {
        assertEquals("45%", Formatters.percent(0.45, "en"))
        assertEquals("7.5%", Formatters.percent(0.075, "en", decimals = 1))
    }

    @Test
    fun `percentages use persian digits and sign in persian`() {
        assertEquals("۴۵٪", Formatters.percent(0.45, "fa"))
        assertEquals("۲٫۵", Formatters.decimal(2.5, "fa", decimals = 1))
    }

    @Test
    fun `invalid numbers render as a dash`() {
        assertEquals("—", Formatters.percent(Double.NaN, "en"))
        assertEquals("—", Formatters.decimal(Double.NaN, "fa"))
    }

    @Test
    fun `relative time buckets are ordered`() {
        val now = 1_700_000_000_000L
        assertEquals(RelativeUnit.NOW, Formatters.relative(now - 5_000, now, "en").unit)
        assertEquals(RelativeUnit.MINUTES, Formatters.relative(now - 10 * TimeConstants.MINUTE_MS, now, "en").unit)
        assertEquals(RelativeUnit.HOURS, Formatters.relative(now - 5 * TimeConstants.HOUR_MS, now, "en").unit)
        assertEquals(RelativeUnit.DAYS, Formatters.relative(now - 3 * TimeConstants.DAY_MS, now, "en").unit)
    }

    @Test
    fun `elapsed minute label is localised`() {
        assertEquals("67'", Formatters.elapsedLabel(67, "en"))
        assertEquals("۶۷'", Formatters.elapsedLabel(67, "fa"))
        assertEquals("", Formatters.elapsedLabel(null, "fa"))
    }

    @Test
    fun `kickoff times follow the device time zone`() {
        // 19 Aug 2023 14:00 UTC, a date when London is on BST and Tehran is UTC+3:30.
        val instant = 1_692_453_600_000L
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("Sat 19 Aug, 14:00", Formatters.kickoffDateTime(instant, "en"))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tehran"))
            assertEquals("Sat 19 Aug, 17:30", Formatters.kickoffDateTime(instant, "en"))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `persian detection is case insensitive`() {
        assertTrue(Formatters.isPersian("fa-IR"))
        assertTrue(!Formatters.isPersian("en-GB"))
    }

    @Test
    fun `a missing score is never rendered as zero`() {
        // The match header used to print "0 - 0" for a live fixture whose score the
        // source had not published yet, which reads as a claim about the score.
        assertEquals("۱ - ۰", Formatters.scoreOrNull(1, 0, "fa"))
        assertEquals("1 - 0", Formatters.scoreOrNull(1, 0, "en"))
        assertNull(Formatters.scoreOrNull(null, 0, "fa"))
        assertNull(Formatters.scoreOrNull(0, null, "en"))
        assertNull(Formatters.scoreOrNull(null, null, "fa"))
    }

    @Test
    fun `digit localisation only touches digits`() {
        assertEquals("۱-۰", Formatters.localizeDigits("1-0", "fa"))
        assertEquals("1-0", Formatters.localizeDigits("1-0", "en"))
    }
}
