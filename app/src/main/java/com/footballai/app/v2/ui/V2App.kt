package com.footballai.app.v2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.footballai.app.v2.V2ViewModel
import com.footballai.app.v2.theme.LocalV2Palette
import java.time.LocalDate

private enum class V2Tab { HOME, PREDICTIONS, HISTORY }

/**
 * The rewritten shell: three tabs in a floating pill bar plus a separate
 * floating search circle, settings as a full-screen page over the tabs.
 *
 * The bar is an overlay on an opaque surface colour — never the Scaffold
 * bottomBar slot (which paints an opaque rectangle behind it) and never
 * translucent (content scrolling beneath a translucent bar ghosts through).
 */
@Composable
fun V2App(viewModel: V2ViewModel) {
    val palette = LocalV2Palette.current
    val settings by viewModel.settings.collectAsState()
    val data by viewModel.data.collectAsState()

    var tab by rememberSaveable { mutableStateOf(V2Tab.HOME) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedDayEpoch by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }

    Scaffold(
        containerColor = palette.background,
        contentColor = palette.ink,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (showSettings) {
                SettingsScreen(
                    settings = settings,
                    data = data,
                    onBack = { showSettings = false },
                    onThemeModeChange = viewModel::setThemeMode,
                    onLeagueVisibleChange = viewModel::setLeagueVisible,
                    onLeagueOrderChange = viewModel::setLeagueOrder,
                    onRefresh = viewModel::refreshNow,
                )
            } else {
                when (tab) {
                    V2Tab.HOME -> HomeScreen(
                        data = data,
                        leagueOrderSlugs = settings.leagueOrder,
                        hiddenLeagues = settings.hiddenLeagues,
                        selectedDay = LocalDate.ofEpochDay(selectedDayEpoch),
                        onSelectDay = { selectedDayEpoch = it.toEpochDay() },
                        searchActive = searchActive,
                        query = query,
                        onQueryChange = { query = it },
                        onCloseSearch = {
                            searchActive = false
                            query = ""
                        },
                        onSettingsClick = { showSettings = true },
                        onRefresh = viewModel::refreshNow,
                    )
                    V2Tab.PREDICTIONS -> PredictionsPlaceholder(
                        onSettingsClick = { showSettings = true },
                    )
                    V2Tab.HISTORY -> HistoryPlaceholder(
                        onSettingsClick = { showSettings = true },
                    )
                }

                FloatingNav(
                    tab = tab,
                    onTabSelected = { tab = it },
                    searchActive = searchActive,
                    onSearchClick = {
                        if (searchActive) {
                            searchActive = false
                            query = ""
                        } else {
                            tab = V2Tab.HOME
                            searchActive = true
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun FloatingNav(
    tab: V2Tab,
    onTabSelected: (V2Tab) -> Unit,
    searchActive: Boolean,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalV2Palette.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
    ) {
        // Pill: the curved ends cut away ~0.215 × radius of visual space, so
        // the horizontal padding is larger than the vertical to read even.
        Row(
            modifier = Modifier
                .height(64.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(palette.barBackground)
                .border(1.dp, palette.hairline, RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(
                icon = Icons.Filled.Home,
                label = "خانه",
                selected = tab == V2Tab.HOME,
                onClick = { onTabSelected(V2Tab.HOME) },
            )
            NavItem(
                icon = Icons.Filled.Star,
                label = "پیش‌بینی‌ها",
                selected = tab == V2Tab.PREDICTIONS,
                onClick = { onTabSelected(V2Tab.PREDICTIONS) },
            )
            NavItem(
                icon = Icons.Filled.History,
                label = "سابقه",
                selected = tab == V2Tab.HISTORY,
                onClick = { onTabSelected(V2Tab.HISTORY) },
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(if (searchActive) palette.accent else palette.barBackground)
                .border(
                    1.dp,
                    if (searchActive) palette.accent else palette.hairline,
                    CircleShape,
                )
                .clickable { onSearchClick() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (searchActive) Icons.Filled.Close else Icons.Filled.Search,
                contentDescription = if (searchActive) "بستن جستجو" else "جستجوی تیم",
                tint = if (searchActive) palette.onAccent else palette.inkMuted,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun NavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalV2Palette.current
    val contentColor = if (selected) palette.accentText else palette.inkMuted
    Column(
        modifier = Modifier
            .width(74.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(999.dp))
            .then(
                if (selected) {
                    Modifier.background(palette.accent.copy(alpha = 0.14f))
                } else {
                    Modifier
                },
            )
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(21.dp),
        )
        Text(
            text = label,
            color = contentColor,
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}
