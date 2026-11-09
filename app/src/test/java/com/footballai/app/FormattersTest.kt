package com.footballai.app

import com.footballai.app.core.Formatters
import com.footballai.app.core.RelativeUnit
import com.footballai.app.core.TimeConstants
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `persian detection is case insensitive`() {
        assertTrue(Formatters.isPersian("fa-IR"))
        assertTrue(!Formatters.isPersian("en-GB"))
    }

    @Test
    fun `digit localisation only touches digits`() {
        assertEquals("۱-۰", Formatters.localizeDigits("1-0", "fa"))
        assertEquals("1-0", Formatters.localizeDigits("1-0", "en"))
    }
}
