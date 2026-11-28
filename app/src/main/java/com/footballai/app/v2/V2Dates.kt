package com.footballai.app.v2

import android.icu.text.DateFormat
import android.icu.util.Calendar
import android.icu.util.ULocale
import com.footballai.app.core.Formatters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Date/time labels for the v2 shell. The interface is Persian, so dates are
 * shown on the Persian calendar with Persian digits (via ICU, like the rest
 * of the app), while day arithmetic stays on [LocalDate] in the device zone.
 */
object V2Dates {

    private val FA_LOCALE = ULocale("fa_IR@calendar=persian")
    private const val FA_TAG = "fa"

    fun parseInstant(iso: String): Instant? = runCatching { Instant.parse(iso) }.getOrNull()

    fun localDate(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        instant.atZone(zone).toLocalDate()

    /** Short weekday name on the Persian calendar, e.g. «شنبه». */
    fun weekdayFa(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        val calendar = persianCalendar(date, zone)
        return DateFormat.getPatternInstance("EEEE", FA_LOCALE).format(calendar)
    }

    /** Day of month on the Persian calendar, with Persian digits, e.g. «۷». */
    fun dayOfMonthFa(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        val calendar = persianCalendar(date, zone)
        return Formatters.localizeDigits(calendar.get(Calendar.DAY_OF_MONTH).toString(), FA_TAG)
    }

    /** «شنبه ۷ آذر» — for the selected-day heading above the groups. */
    fun fullDayFa(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        val calendar = persianCalendar(date, zone)
        val weekdayAndDay = DateFormat.getPatternInstance("EEEE", FA_LOCALE)
            .format(calendar)
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val month = DateFormat.getPatternInstance("MMMM", FA_LOCALE).format(calendar)
        return Formatters.localizeDigits("$weekdayAndDay $day $month", FA_TAG)
    }

    /** Kickoff clock time with Persian digits, e.g. «۱۸:۰۰». */
    fun timeFa(instant: Instant): String =
        Formatters.timeOnly(instant.toEpochMilli(), FA_TAG)

    /** Full date-time for the settings freshness line, e.g. «شنبه ۷ آذر ۱۴۰۵، ۱۴:۲۷». */
    fun dateTimeFa(epochMs: Long): String = Formatters.kickoffDateTime(epochMs, FA_TAG)

    private fun persianCalendar(date: LocalDate, zone: ZoneId): Calendar {
        val calendar = Calendar.getInstance(FA_LOCALE)
        val startOfDay = date.atStartOfDay(zone).toInstant()
        calendar.timeInMillis = startOfDay.toEpochMilli()
        return calendar
    }
}
