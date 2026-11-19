package com.footballai.app.core

import android.icu.util.Calendar
import android.icu.util.ULocale
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Calendar arithmetic is done on local civil dates, not UTC instants. */
internal object MatchCalendarDates {
    const val MAX_DAY_OFFSET = 3650

    data class Month(val first: LocalDate, val length: Int, val year: Int, val month: Int) {
        val last: LocalDate get() = first.plusDays((length - 1).toLong())
        fun intersects(min: LocalDate, max: LocalDate): Boolean = !last.isBefore(min) && !first.isAfter(max)
    }

    fun leadingDays(first: LocalDate, firstDayOfWeek: DayOfWeek): Int =
        (first.dayOfWeek.value - firstDayOfWeek.value + 7) % 7

    fun month(date: LocalDate, persian: Boolean, zone: ZoneId): Month {
        if (!persian) return Month(date.withDayOfMonth(1), date.lengthOfMonth(), date.year, date.monthValue)
        val calendar = persianCalendar(date, zone)
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return Month(date.minusDays((day - 1).toLong()), calendar.getActualMaximum(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
    }

    fun shift(month: Month, amount: Int, persian: Boolean, zone: ZoneId): Month {
        if (!persian) return month(month.first.plusMonths(amount.toLong()), false, zone)
        val calendar = persianCalendar(month.first, zone)
        calendar.add(Calendar.MONTH, amount)
        val date = java.time.Instant.ofEpochMilli(calendar.timeInMillis).atZone(zone).toLocalDate()
        return month(date, true, zone)
    }

    fun monthLabel(month: Month, persian: Boolean, languageTag: String): String {
        val names = if (persian) listOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
        else java.time.Month.values().map { it.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) }
        return "${names[month.month - 1]} ${Formatters.integer(month.year, languageTag)}"
    }

    private fun persianCalendar(date: LocalDate, zone: ZoneId): Calendar =
        Calendar.getInstance(android.icu.util.TimeZone.getTimeZone(zone.id), ULocale("fa_IR@calendar=persian")).apply {
            timeInMillis = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        }
}
