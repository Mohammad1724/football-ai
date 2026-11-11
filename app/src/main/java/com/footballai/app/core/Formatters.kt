package com.footballai.app.core

import android.icu.text.DateFormat
import android.icu.util.Calendar
import android.icu.util.ULocale
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Formatting helpers shared by every screen.
 *
 * Persian (fa) output uses the Persian calendar and Persian digits, English output
 * uses the Gregorian calendar and Latin digits. Formatting never leaks the device
 * locale into the model layer: the language tag is always passed explicitly.
 */
object Formatters {

    private const val PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"

    fun isPersian(languageTag: String): Boolean = languageTag.lowercase(Locale.ROOT).startsWith("fa")

    /** Converts ASCII digits to Persian digits so numbers read naturally in RTL. */
    fun localizeDigits(text: String, languageTag: String): String {
        if (!isPersian(languageTag)) return text
        val builder = StringBuilder(text.length)
        for (char in text) {
            builder.append(if (char in '0'..'9') PERSIAN_DIGITS[char - '0'] else char)
        }
        return builder.toString()
    }

    fun percent(fraction: Double, languageTag: String, decimals: Int = 0): String {
        if (fraction.isNaN()) return "—"
        val text = String.format(Locale.US, "%.${decimals}f", fraction * 100.0)
        val symbol = if (isPersian(languageTag)) "٪" else "%"
        return localizeDigits(text, languageTag) + symbol
    }

    fun decimal(value: Double, languageTag: String, decimals: Int = 2): String {
        if (value.isNaN() || value.isInfinite()) return "—"
        val text = String.format(Locale.US, "%.${decimals}f", value)
        // Persian typography uses the Arabic decimal separator (U+066B).
        if (isPersian(languageTag)) return localizeDigits(text.replace('.', '\u066B'), languageTag)
        return text
    }

    fun integer(value: Int, languageTag: String): String = localizeDigits(value.toString(), languageTag)

    fun signedInteger(value: Int, languageTag: String): String {
        val prefix = if (value > 0) "+" else ""
        return localizeDigits("$prefix$value", languageTag)
    }

    /** "12 minutes ago"-style relative label; the UI turns it into a phrase. */
    fun relative(epochMs: Long, nowMs: Long, languageTag: String): RelativeLabel {
        val deltaMs = abs(nowMs - epochMs)
        val minutes = (deltaMs / TimeConstants.MINUTE_MS).toInt()
        val hours = (deltaMs / TimeConstants.HOUR_MS).toInt()
        val days = (deltaMs / TimeConstants.DAY_MS).toInt()
        return when {
            minutes < 1 -> RelativeLabel(RelativeUnit.NOW, 0)
            minutes < 60 -> RelativeLabel(RelativeUnit.MINUTES, minutes)
            hours < 24 -> RelativeLabel(RelativeUnit.HOURS, hours)
            else -> RelativeLabel(RelativeUnit.DAYS, days)
        }
    }

    /** Kickoff time in the user's locale (Persian calendar for fa). */
    fun kickoffDateTime(epochMs: Long, languageTag: String): String {
        if (epochMs <= 0L) return "—"
        if (isPersian(languageTag)) {
            return PersianDates.format(epochMs, includeWeekday = true, includeYear = false) +
                "، " + PersianDates.formatTime(epochMs)
        }
        val formatter = java.time.format.DateTimeFormatter
            .ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)
            .withZone(java.time.ZoneId.systemDefault())
        return formatter.format(java.time.Instant.ofEpochMilli(epochMs))
    }

    fun dateOnly(epochMs: Long, languageTag: String): String {
        if (epochMs <= 0L) return "—"
        if (isPersian(languageTag)) return PersianDates.format(epochMs, includeWeekday = true, includeYear = true)
        val formatter = java.time.format.DateTimeFormatter
            .ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
            .withZone(java.time.ZoneId.systemDefault())
        return formatter.format(java.time.Instant.ofEpochMilli(epochMs))
    }

    fun timeOnly(epochMs: Long, languageTag: String): String {
        if (epochMs <= 0L) return "—"
        if (isPersian(languageTag)) return PersianDates.formatTime(epochMs)
        return java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(epochMs))
    }

    fun roundToIntSafe(value: Double): Int = if (value.isNaN()) 0 else value.roundToInt()

    fun elapsedLabel(minute: Int?, languageTag: String): String {
        if (minute == null) return ""
        return localizeDigits("$minute'", languageTag)
    }
}

enum class RelativeUnit { NOW, MINUTES, HOURS, DAYS }

data class RelativeLabel(val unit: RelativeUnit, val value: Int)

/** Persian (Jalali) calendar formatting backed by ICU, available on Android 8+. */
object PersianDates {

    fun format(epochMs: Long, includeWeekday: Boolean, includeYear: Boolean): String = try {
        val pattern = buildString {
            if (includeWeekday) append("EEEE ")
            append("d MMMM")
            if (includeYear) append(" yyyy")
        }
        val locale = ULocale("fa_IR@calendar=persian")
        val calendar = Calendar.getInstance(locale)
        calendar.timeInMillis = epochMs
        val dateFormat = DateFormat.getInstanceForSkeleton(pattern, locale)
        dateFormat.calendar = calendar
        Formatters.localizeDigits(dateFormat.format(calendar.time), "fa")
    } catch (error: Exception) {
        java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(epochMs))
    }

    fun formatTime(epochMs: Long): String = try {
        val locale = ULocale("fa_IR@calendar=persian")
        val calendar = Calendar.getInstance(locale)
        calendar.timeInMillis = epochMs
        val timeFormat = DateFormat.getInstanceForSkeleton("HH:mm", locale)
        timeFormat.calendar = calendar
        Formatters.localizeDigits(timeFormat.format(calendar.time), "fa")
    } catch (error: Exception) {
        java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(epochMs))
    }
}
