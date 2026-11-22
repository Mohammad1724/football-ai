package com.footballai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.footballai.app.R
import com.footballai.app.core.Formatters
import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.domain.model.MatchStatus
import com.footballai.app.domain.model.MatchSummary
import com.footballai.app.domain.model.TeamRef
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.components.TeamBadge
import com.footballai.app.ui.components.TeamNameLines
import com.footballai.app.ui.viewmodel.MatchSearchFilter
import com.footballai.app.ui.viewmodel.MatchesUiState

/** Search only the downloaded data; the keyboard and result list share the available height. */
@Composable
internal fun MatchSearchDialog(
    state: MatchesUiState,
    onQueryChange: (String) -> Unit,
    onFilterChange: (MatchSearchFilter) -> Unit,
    onDismiss: () -> Unit,
    onCompetitionClick: (String) -> Unit,
    onTeamClick: (TeamRef) -> Unit,
    onMatchClick: (MatchSummary) -> Unit,
) {
    // Dialogs create another window, which may use the system locale instead of the app's.
    // Capture app-localized resources before entering it (as the filters dialog does).
    val resources = LocalContext.current.resources
    val languageTag = LocalLanguageTag.current
    val direction = LocalLayoutDirection.current
    val hasVisibleResults = when (state.searchFilter) {
        MatchSearchFilter.ALL -> !state.searchResults.isEmpty
        MatchSearchFilter.COMPETITIONS -> state.searchResults.competitions.isNotEmpty()
        MatchSearchFilter.TEAMS -> state.searchResults.teams.isNotEmpty()
        MatchSearchFilter.MATCHES -> state.searchResults.matches.isNotEmpty()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = remember { FocusRequester() }
        // The dialog itself is a screen: unlike a fixed-height popup it remains usable with
        // a keyboard, large fonts, landscape, and a small phone. The results alone scroll.
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Surface(modifier = Modifier.fillMaxSize().imePadding(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compact = maxHeight < 360.dp
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = resources.getString(R.string.action_close))
                    }
                    Text(resources.getString(R.string.search_matches), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
                    singleLine = true,
                    placeholder = { Text(resources.getString(R.string.search_matches_hint), maxLines = 1) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = if (state.searchQuery.isNotEmpty()) {
                        { IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = resources.getString(R.string.search_clear))
                        } }
                    } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                )
                if (!compact) {
                    Text(resources.getString(R.string.search_local_description),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(MatchSearchFilter.entries, key = { it.name }) { filter ->
                        val label = when (filter) {
                            MatchSearchFilter.ALL -> R.string.search_all
                            MatchSearchFilter.COMPETITIONS -> R.string.search_competitions
                            MatchSearchFilter.TEAMS -> R.string.search_teams
                            MatchSearchFilter.MATCHES -> R.string.search_match_results
                        }
                        FilterChip(selected = state.searchFilter == filter, onClick = { onFilterChange(filter) },
                            colors = matchFilterChipColors(), label = { Text(resources.getString(label)) })
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                when {
                    state.searchQuery.isBlank() -> SearchMessage(resources.getString(R.string.search_start), Modifier.weight(1f))
                    state.searchLoading -> Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
                    }
                    !hasVisibleResults -> SearchMessage(resources.getString(
                        if (state.searchFilter == MatchSearchFilter.ALL) R.string.no_search_results else R.string.search_no_category_results
                    ), Modifier.weight(1f))
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.searchFilter == MatchSearchFilter.ALL || state.searchFilter == MatchSearchFilter.COMPETITIONS) {
                            if (state.searchResults.competitions.isNotEmpty()) {
                                item(key = "heading-leagues") { SearchSectionTitle(resources.getString(R.string.search_competitions)) }
                                items(state.searchResults.competitions, key = { "league-${it.id}" }) { competition ->
                                    SearchEntityRow(
                                        title = NameLocalization.competitionNamePair(competition.name, competition.id, languageTag).first,
                                        subtitle = competition.country ?: resources.getString(R.string.search_competitions),
                                        icon = { Icon(Icons.Rounded.Groups, contentDescription = null) },
                                        onClick = { onCompetitionClick(competition.id) },
                                    )
                                }
                            }
                        }
                        if (state.searchFilter == MatchSearchFilter.ALL || state.searchFilter == MatchSearchFilter.TEAMS) {
                            if (state.searchResults.teams.isNotEmpty()) {
                                item(key = "heading-teams") { SearchSectionTitle(resources.getString(R.string.search_teams)) }
                                items(state.searchResults.teams, key = { "team-${it.id}" }) { team ->
                                    SearchEntityRow(
                                        title = NameLocalization.displayName(team.name, languageTag, team.shortName),
                                        subtitle = NameLocalization.namePair(team.name, languageTag, team.shortName).second
                                            ?: resources.getString(R.string.search_team_matches),
                                        icon = { TeamBadge(team, size = 30) },
                                        onClick = { onTeamClick(team) },
                                    )
                                }
                            }
                        }
                        if (state.searchFilter == MatchSearchFilter.ALL || state.searchFilter == MatchSearchFilter.MATCHES) {
                            if (state.searchResults.matches.isNotEmpty()) {
                                item(key = "heading-matches") { SearchSectionTitle(resources.getString(R.string.search_match_results)) }
                                items(state.searchResults.matches, key = { "match-${it.id}" }) { match ->
                                    SearchMatchRow(match, languageTag, resources, onClick = { onMatchClick(match) })
                                }
                            }
                        }
                    }
                }
            }
            }
        }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

@Composable
private fun SearchMessage(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
    }
}

@Composable
private fun SearchSectionTitle(title: String) {
    Text(title, modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SearchEntityRow(title: String, subtitle: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SearchMatchRow(match: MatchSummary, languageTag: String, resources: android.content.res.Resources, onClick: () -> Unit) {
    val competition = NameLocalization.competitionNamePair(match.competitionName, match.competitionId, languageTag).first
    val status = when (match.status) {
        MatchStatus.LIVE -> resources.getString(R.string.status_live)
        MatchStatus.HALF_TIME -> resources.getString(R.string.status_half_time)
        MatchStatus.FINISHED -> resources.getString(R.string.status_full_time_short)
        MatchStatus.POSTPONED -> resources.getString(R.string.status_postponed)
        MatchStatus.SUSPENDED -> resources.getString(R.string.status_suspended)
        MatchStatus.CANCELLED -> resources.getString(R.string.status_cancelled)
        MatchStatus.UNKNOWN -> resources.getString(R.string.status_unknown)
        MatchStatus.SCHEDULED -> ""
    }
    val value = when {
        match.status.hasScore -> Formatters.scoreOrNull(match.homeGoals, match.awayGoals, languageTag) ?: "—"
        match.status == MatchStatus.SCHEDULED -> Formatters.timeOnly(match.kickoffEpochMs, languageTag)
        else -> status
    }
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("$competition · ${Formatters.dateOnlyShort(match.kickoffEpochMs, languageTag)}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(match.home, match.away).forEach { team ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TeamBadge(team, size = 24)
                            Spacer(Modifier.width(8.dp))
                            TeamNameLines(team = team, languageTag = languageTag,
                                primaryStyle = MaterialTheme.typography.bodyMedium,
                                primaryMaxLines = 2, secondaryMaxLines = 2)
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 8.dp)) {
                    if (status.isNotEmpty() && match.status.hasScore) {
                        Text(status, style = MaterialTheme.typography.labelSmall,
                            color = if (match.status.isLive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
