package com.footballai.app.v2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.footballai.app.core.Formatters
import com.footballai.app.v2.V2Dates
import com.footballai.app.v2.V2League
import com.footballai.app.v2.V2Leagues
import com.footballai.app.v2.data.V2DataState
import com.footballai.app.v2.data.V2FixtureDto
import com.footballai.app.v2.data.V2LiveMatchDto
import com.footballai.app.v2.theme.LocalV2Palette
import java.time.LocalDate

/**
 * Home (spec steps 1+3): a slim one-row timeline bar — dates only, today
 * highlighted, scrollable from −3 days to +7 — switching the single day shown
 * below, grouped by league in the user's order. There is deliberately no
 * long all-days scroll.
 *
 * While a search query is present the day bar steps aside and the timeline
 * is filtered by team name across the whole window (search filters the
 * timeline only — spec step 3).
 */
@Composable
fun HomeScreen(
    data: V2DataState,
    leagueOrderSlugs: List<String>,
    hiddenLeagues: Set<String>,
    selectedDay: LocalDate,
    onSelectDay: (LocalDate) -> Unit,
    searchActive: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
    onSettingsClick: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalV2Palette.current
    val today = remember { LocalDate.now() }
    val days = remember(today) { (-3..7).map { today.plusDays(it.toLong()) } }
    val searching = searchActive && query.isNotBlank()

    val visibleLeagues = remember(leagueOrderSlugs, hiddenLeagues) {
        V2Leagues.resolveOrder(leagueOrderSlugs).filter { it.slug !in hiddenLeagues }
    }
    val liveById = remember(data.live) {
        data.live?.matches?.associateBy { it.id } ?: emptyMap()
    }

    val groups: List<Pair<V2League, List<V2FixtureDto>>> = remember(
        data.bundles, visibleLeagues, selectedDay, searching, query,
    ) {
        visibleLeagues.mapNotNull { league ->
            val bundle = data.bundles[league.slug] ?: return@mapNotNull null
            val fixtures = bundle.fixtures
                .filter { fixture ->
                    if (searching) {
                        fixture.matchesQuery(query)
                    } else {
                        val kickoff = V2Dates.parseInstant(fixture.kickoffUtc)
                        kickoff != null && V2Dates.localDate(kickoff) == selectedDay
                    }
                }
                .sortedBy { it.kickoffUtc }
            if (fixtures.isEmpty()) null else league to fixtures
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        V2TopBar(title = "خانه", onSettingsClick = onSettingsClick)

        if (searchActive) {
            SearchField(
                query = query,
                onQueryChange = onQueryChange,
                onCloseSearch = onCloseSearch,
            )
        } else {
            DayBar(
                days = days,
                today = today,
                selectedDay = selectedDay,
                onSelectDay = onSelectDay,
            )
        }

        when {
            !data.loaded -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = palette.accent, modifier = Modifier.size(28.dp))
                    Text(
                        text = "در حال بارگذاری…",
                        color = palette.inkMuted,
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }

            data.bundles.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                V2EmptyState(
                    title = "داده‌ای در دسترس نیست",
                    subtitle = "بسته‌ی داده بارگذاری نشد. اتصال را بررسی و دوباره تلاش کن.",
                )
                TextButton(onClick = onRefresh) {
                    Text(text = "تلاش دوباره", color = palette.accentText, fontSize = 13.sp)
                }
            }

            visibleLeagues.isEmpty() -> V2EmptyState(
                title = "همه‌ی لیگ‌ها خاموش‌اند",
                subtitle = "از تنظیمات، لیگ‌های نمایشی را روشن کن.",
                modifier = Modifier.fillMaxSize(),
            )

            groups.isEmpty() -> V2EmptyState(
                title = if (searching) "بازی‌ای پیدا نشد" else "در این روز بازی‌ای نیست",
                subtitle = if (searching) {
                    "تیمی با این نام در بازه‌ی فعلی پیدا نشد."
                } else {
                    "روز دیگری را از نوار بالا انتخاب کن."
                },
                modifier = Modifier.fillMaxSize(),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 14.dp, end = 14.dp, top = 4.dp, bottom = V2FloatingBarInset,
                ),
                verticalArrangement = Arrangement.spacedBy(V2CardGap),
            ) {
                if (searching) {
                    item(key = "search-heading") {
                        V2SectionLabel(text = "نتایج جستجو")
                    }
                } else {
                    item(key = "day-heading") {
                        val total = groups.sumOf { it.second.size }
                        V2SectionLabel(
                            text = "${V2Dates.fullDayFa(selectedDay)} — " +
                                "${Formatters.localizeDigits(total.toString(), "fa")} بازی",
                        )
                    }
                }
                items(groups, key = { it.first.slug }) { (league, fixtures) ->
                    LeagueGroupCard(
                        league = league,
                        fixtures = fixtures,
                        liveById = liveById,
                    )
                }
            }
        }
    }
}

private fun V2FixtureDto.matchesQuery(query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return false
    return home.name.lowercase().contains(needle) ||
        home.shortName?.lowercase()?.contains(needle) == true ||
        away.name.lowercase().contains(needle) ||
        away.shortName?.lowercase()?.contains(needle) == true
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit,
) {
    val palette = LocalV2Palette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(text = "نام تیم…", color = palette.inkFaint, fontSize = 13.sp)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = palette.accent,
                unfocusedBorderColor = palette.hairline,
                focusedContainerColor = palette.card,
                unfocusedContainerColor = palette.card,
                focusedTextColor = palette.ink,
                unfocusedTextColor = palette.ink,
                cursorColor = palette.accent,
            ),
        )
        IconButton(onClick = onCloseSearch, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "بستن جستجو",
                tint = palette.inkMuted,
            )
        }
    }
}

@Composable
private fun DayBar(
    days: List<LocalDate>,
    today: LocalDate,
    selectedDay: LocalDate,
    onSelectDay: (LocalDate) -> Unit,
) {
    val palette = LocalV2Palette.current
    val listState = rememberLazyListState()
    // Open scrolled to today (index 3): the past days are one swipe away,
    // the week ahead is what the bar is mostly for.
    LaunchedEffect(Unit) {
        val todayIndex = days.indexOf(today)
        if (todayIndex > 0) listState.scrollToItem(todayIndex - 1)
    }
    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(days, key = { it.toEpochDay() }) { day ->
            val selected = day == selectedDay
            val isToday = day == today
            val background = if (selected) palette.selectedDay else palette.card
            val labelColor = when {
                selected -> palette.onSelectedDay
                isToday -> palette.accentText
                else -> palette.inkMuted
            }
            val numberColor = when {
                selected -> palette.onSelectedDay
                else -> palette.ink
            }
            Column(
                modifier = Modifier
                    .width(52.dp)
                    .height(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(background)
                    .border(
                        width = 1.dp,
                        color = if (selected) palette.selectedDay else palette.hairline,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelectDay(day) }
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = if (isToday) "امروز" else V2Dates.weekdayFa(day),
                    color = labelColor,
                    fontSize = 10.5.sp,
                    fontWeight = if (isToday || selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
                Text(
                    text = V2Dates.dayOfMonthFa(day),
                    color = numberColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun LeagueGroupCard(
    league: V2League,
    fixtures: List<V2FixtureDto>,
    liveById: Map<Long, V2LiveMatchDto>,
) {
    val palette = LocalV2Palette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.card)
            .border(1.dp, palette.hairline, RoundedCornerShape(16.dp)),
    ) {
        Text(
            text = league.nameFa,
            color = palette.ink,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
        )
        fixtures.forEachIndexed { index, fixture ->
            if (index > 0) {
                HorizontalDivider(
                    color = palette.rowDivider,
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
            V2MatchRow(fixture = fixture, live = liveById[fixture.id])
        }
        Box(modifier = Modifier.height(6.dp))
    }
}
