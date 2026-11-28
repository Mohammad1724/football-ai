package com.footballai.app.v2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.footballai.app.v2.V2Dates
import com.footballai.app.v2.V2League
import com.footballai.app.v2.V2Leagues
import com.footballai.app.v2.data.V2DataState
import com.footballai.app.v2.data.V2Settings
import com.footballai.app.v2.data.V2ThemeMode
import com.footballai.app.v2.theme.LocalV2Palette
import kotlin.math.roundToInt

/**
 * The one sparse settings page of the rewrite (spec step 3): league
 * visibility + drag order, day/night/auto theme, data freshness with a
 * manual refresh, and a quiet about footer. There are no API-key or
 * data-source settings — the app has exactly one source.
 */
@Composable
fun SettingsScreen(
    settings: V2Settings,
    data: V2DataState,
    onBack: () -> Unit,
    onThemeModeChange: (V2ThemeMode) -> Unit,
    onLeagueVisibleChange: (String, Boolean) -> Unit,
    onLeagueOrderChange: (List<String>) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalV2Palette.current
    var leagueDragging by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "بازگشت",
                    tint = palette.ink,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = "تنظیمات",
                color = palette.ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState(), enabled = !leagueDragging)
                .padding(horizontal = 14.dp),
        ) {
            // --- Leagues: visibility + order -------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                V2SectionLabel(text = "لیگ‌های نمایشی و ترتیب‌شون")
                Text(
                    text = "با ≡ بگیر و بکش تا جابه‌جا بشن",
                    color = palette.accentText,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            LeagueOrderCard(
                settings = settings,
                onLeagueVisibleChange = onLeagueVisibleChange,
                onLeagueOrderChange = onLeagueOrderChange,
                onDragActiveChange = { leagueDragging = it },
            )

            // --- Theme ------------------------------------------------------
            V2SectionLabel(
                text = "حالت نمایش",
                modifier = Modifier.padding(top = 18.dp),
            )
            ThemeModeSegmented(
                mode = settings.themeMode,
                onThemeModeChange = onThemeModeChange,
            )

            // --- Data freshness ---------------------------------------------
            V2SectionLabel(
                text = "داده‌ها",
                modifier = Modifier.padding(top = 18.dp),
            )
            DataFreshnessCard(
                settings = settings,
                data = data,
                onRefresh = onRefresh,
            )

            // --- About --------------------------------------------------------
            AboutFooter(
                modifier = Modifier.padding(top = 22.dp, bottom = 36.dp),
            )
        }
    }
}

private val LeagueRowHeight = 58.dp

@Composable
private fun LeagueOrderCard(
    settings: V2Settings,
    onLeagueVisibleChange: (String, Boolean) -> Unit,
    onLeagueOrderChange: (List<String>) -> Unit,
    onDragActiveChange: (Boolean) -> Unit,
) {
    val palette = LocalV2Palette.current
    // Local copy drives the drag; the persisted order is written once, when
    // the drag ends, so DataStore is not hammered with every intermediate swap.
    var order by remember(settings.leagueOrder) {
        mutableStateOf(V2Leagues.resolveOrder(settings.leagueOrder))
    }
    var draggingSlug by remember { mutableStateOf<String?>(null) }
    var dragOffsetPx by remember { mutableStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.card)
            .border(1.dp, palette.hairline, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp),
    ) {
        order.forEachIndexed { index, league ->
            val isDragging = draggingSlug == league.slug
            if (index > 0) {
                HorizontalDivider(color = palette.rowDivider, thickness = 1.dp)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LeagueRowHeight)
                    .zIndex(if (isDragging) 1f else 0f)
                    .offset {
                        IntOffset(0, if (isDragging) dragOffsetPx.roundToInt() else 0)
                    }
                    .then(
                        if (isDragging) Modifier.background(palette.card) else Modifier,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = "جابه‌جایی ${league.nameFa}",
                    tint = palette.inkFaint,
                    modifier = Modifier
                        .size(48.dp)
                        .pointerInput(league.slug) {
                            val rowPx = LeagueRowHeight.toPx()
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingSlug = league.slug
                                    dragOffsetPx = 0f
                                    onDragActiveChange(true)
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffsetPx += dragAmount.y
                                    val from = order.indexOfFirst { it.slug == league.slug }
                                    if (from >= 0) {
                                        val target = (from + (dragOffsetPx / rowPx).roundToInt())
                                            .coerceIn(0, order.lastIndex)
                                        if (target != from) {
                                            order = order.moved(from, target)
                                            dragOffsetPx -= (target - from) * rowPx
                                        }
                                    }
                                },
                                onDragEnd = {
                                    onLeagueOrderChange(order.map { it.slug })
                                    draggingSlug = null
                                    dragOffsetPx = 0f
                                    onDragActiveChange(false)
                                },
                                onDragCancel = {
                                    // Keep what is on screen: a cancelled drag
                                    // persists the visible order instead of
                                    // leaving the UI and the store disagreeing.
                                    onLeagueOrderChange(order.map { it.slug })
                                    draggingSlug = null
                                    dragOffsetPx = 0f
                                    onDragActiveChange(false)
                                },
                            )
                        }
                        // Padding after the gesture modifier: the hit area
                        // stays the full 48dp, the glyph renders at 24dp.
                        .padding(12.dp),
                )
                Text(
                    text = league.settingsLabelFa,
                    color = palette.ink,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = league.slug !in settings.hiddenLeagues,
                    onCheckedChange = { visible ->
                        onLeagueVisibleChange(league.slug, visible)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = palette.onAccent,
                        checkedTrackColor = palette.accent,
                        uncheckedThumbColor = palette.inkFaint,
                        uncheckedTrackColor = palette.hairline,
                        uncheckedBorderColor = palette.hairline,
                    ),
                )
            }
        }
    }
}

private fun List<V2League>.moved(from: Int, to: Int): List<V2League> {
    if (from == to) return this
    return toMutableList().apply {
        val item = removeAt(from)
        add(to, item)
    }
}

@Composable
private fun ThemeModeSegmented(
    mode: V2ThemeMode,
    onThemeModeChange: (V2ThemeMode) -> Unit,
) {
    val palette = LocalV2Palette.current
    val options = listOf(
        V2ThemeMode.DAY to "روز",
        V2ThemeMode.NIGHT to "شب",
        V2ThemeMode.AUTO to "خودکار",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(palette.card)
            .border(1.dp, palette.hairline, RoundedCornerShape(999.dp))
            .padding(3.dp),
    ) {
        options.forEach { (option, label) ->
            val selected = mode == option
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (selected) palette.accent else palette.card.copy(alpha = 0f))
                    .clickable { onThemeModeChange(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (selected) palette.onAccent else palette.inkMuted,
                    fontSize = 12.5.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun DataFreshnessCard(
    settings: V2Settings,
    data: V2DataState,
    onRefresh: () -> Unit,
) {
    val palette = LocalV2Palette.current
    val freshnessText = when {
        settings.lastRefreshEpochMs != null ->
            "آخرین به‌روزرسانی: ${V2Dates.dateTimeFa(settings.lastRefreshEpochMs)}"
        data.dataGeneratedAt != null ->
            "زمان داده‌ها: ${V2Dates.dateTimeFa(data.dataGeneratedAt.toEpochMilli())}"
        else -> "هنوز داده‌ای بارگذاری نشده"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.card)
            .border(1.dp, palette.hairline, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = freshnessText,
                    color = palette.ink,
                    fontSize = 12.5.sp,
                )
                Text(
                    text = "به‌روزرسانی خودکار: روزی یک بار",
                    color = palette.inkMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Box(
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (data.refreshing) palette.hairline else palette.accent)
                    .clickable(enabled = !data.refreshing) { onRefresh() }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (data.refreshing) {
                    CircularProgressIndicator(
                        color = palette.inkMuted,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Text(
                        text = "به‌روزرسانی الان",
                        color = palette.onAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        if (data.refreshFailed) {
            Text(
                text = "به‌روزرسانی ناموفق بود؛ داده‌ی قبلی سر جاست.",
                color = palette.live,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun AboutFooter(modifier: Modifier = Modifier) {
    val palette = LocalV2Palette.current
    val context = LocalContext.current
    val appVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "نسخه‌ی اپ $appVersion — موتور پیش‌بینی ۱٫۹٫۰",
            color = palette.inkFaint,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "منبع داده: FotMob",
            color = palette.inkFaint,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
