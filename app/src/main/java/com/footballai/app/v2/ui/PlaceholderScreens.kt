package com.footballai.app.v2.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Predictions tab placeholder for the skeleton slice. The real tab (top
 * picks first, then all upcoming predictions grouped by league/day) is a
 * later slice; this is a neutral empty state in the design language, not a
 * "coming soon" promise.
 */
@Composable
fun PredictionsPlaceholder(
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        V2TopBar(title = "پیش‌بینی‌ها", onSettingsClick = onSettingsClick)
        V2EmptyState(
            title = "هنوز پیش‌بینی‌ای برای نمایش نیست",
            subtitle = "پیش‌بینی بازی‌های پیش رو — اول مطمئن‌ترین‌ها، بعد همه بر اساس لیگ و روز — این‌جا می‌آید.",
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = V2FloatingBarInset),
        )
    }
}

/**
 * History tab placeholder for the skeleton slice. The real tab (overall
 * accuracy, 7/30-day/all-time ranges, per-confidence and per-league splits)
 * is a later slice.
 */
@Composable
fun HistoryPlaceholder(
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        V2TopBar(title = "سابقه", onSettingsClick = onSettingsClick)
        V2EmptyState(
            title = "هنوز پیش‌بینی تسویه‌شده‌ای نیست",
            subtitle = "به‌محض تمام شدن بازی‌های پیش‌بینی‌شده، سابقه‌ی دقت پیش‌بینی‌ها این‌جا ساخته می‌شود.",
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = V2FloatingBarInset),
        )
    }
}
