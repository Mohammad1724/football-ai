package com.footballai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.footballai.app.R
import com.footballai.app.core.Formatters
import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.domain.model.ResultOutcome
import com.footballai.app.domain.model.TableVenue
import com.footballai.app.domain.model.TeamProfile
import com.footballai.app.domain.model.TeamResult
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.localizedString
import com.footballai.app.ui.components.SoftBackButton
import com.footballai.app.ui.components.EmptyState
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.components.SectionHeader
import com.footballai.app.ui.components.SoftCard
import com.footballai.app.ui.components.StatusChip
import com.footballai.app.ui.components.TeamBadge
import com.footballai.app.ui.components.TeamNameLines
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.theme.Radii
import com.footballai.app.ui.components.LeagueTableBlock
import com.footballai.app.ui.components.LeagueTableEmpty
import com.footballai.app.ui.components.TableProvenanceNote
import com.footballai.app.ui.components.VenueChips
import com.footballai.app.ui.theme.Spacing
import com.footballai.app.ui.viewmodel.LeagueTableUiState
import com.footballai.app.ui.viewmodel.TeamViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(
    teamId: String,
    onBack: () -> Unit,
    onCompetitionClick: (String) -> Unit,
    onTeamClick: (String) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val container = LocalAppContainer.current
    val viewModel: TeamViewModel = viewModel(
        key = teamId,
        factory = viewModelFactory { initializer { TeamViewModel(container, teamId) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val languageTag = LocalLanguageTag.current
    val profile = state.profile

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    SoftBackButton(
                        onClick = onBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                },
                title = {
                    // The bar follows the same rule as every other team name on the screen.
                    Text(
                        profile?.let {
                            NameLocalization.displayName(it.team.name, languageTag, it.team.shortName)
                        } ?: stringResource(R.string.title_team),
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isLoading) {
                LoadingState()
                return@Column
            }
            if (profile == null) {
                EmptyState(
                    title = stringResource(R.string.empty_team_title),
                    message = stringResource(R.string.empty_team_message),
                )
                return@Column
            }
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.team_tab_summary), style = MaterialTheme.typography.labelMedium) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.tab_table), style = MaterialTheme.typography.labelMedium) },
                )
            }
            if (selectedTab == 1) {
                TeamTableTab(
                    profile = profile,
                    table = state.table,
                    onVenue = viewModel::setVenue,
                    onOpenFullTable = { onCompetitionClick(profile.competitionId) },
                    onTeamClick = onTeamClick,
                )
                return@Column
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TeamBadge(profile.team, size = 52)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            TeamNameLines(
                                team = profile.team,
                                languageTag = languageTag,
                                primaryStyle = MaterialTheme.typography.headlineSmall,
                                primaryMaxLines = 2,
                            )
                            // The competition name also opens its full table page.
                            Text(
                                text = profile.competitionName.ifBlank { stringResource(R.string.unknown_competition) },
                                style = MaterialTheme.typography.bodySmall,
                                color = FootballAiThemeExtra.colors.iconMuted,
                                modifier = Modifier
                                    .clip(Radii.smallShape)
                                    .clickable { onCompetitionClick(profile.competitionId) },
                            )
                        }
                    }
                }
                item { SectionHeader(title = stringResource(R.string.section_team_strength)) }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RatingCard(
                            label = stringResource(R.string.rating_attack),
                            value = Formatters.decimal(profile.rating.attack, languageTag, 2),
                            hint = stringResource(R.string.rating_attack_hint),
                            modifier = Modifier.weight(1f),
                        )
                        RatingCard(
                            label = stringResource(R.string.rating_defence),
                            value = Formatters.decimal(profile.rating.defence, languageTag, 2),
                            hint = stringResource(R.string.rating_defence_hint),
                            modifier = Modifier.weight(1f),
                        )
                        RatingCard(
                            label = stringResource(R.string.rating_elo),
                            value = Formatters.decimal(profile.rating.elo, languageTag, 0),
                            hint = stringResource(R.string.rating_elo_hint),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.section_form),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        profile.form.sequence.take(6).forEach { outcome -> FormDot(outcome) }
                        Text(
                            text = localizedString(
                                R.string.form_summary,
                                profile.form.wins,
                                profile.form.draws,
                                profile.form.losses,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item { SectionHeader(title = stringResource(R.string.section_recent_results)) }
                if (profile.recentMatches.isEmpty()) {
                    item {
                        EmptyState(
                            title = stringResource(R.string.empty_recent_title),
                            message = stringResource(R.string.empty_recent_message),
                        )
                    }
                } else {
                    items(profile.recentMatches) { result ->
                        ResultRow(result = result, languageTag = languageTag)
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/**
 * The club's league, as its own tab.
 *
 * A club page that hides the table behind a link makes the reader leave the page to answer
 * "where do they stand"; this draws it here, with the club's own row marked, and keeps the
 * full page one tap away for the league's overall numbers.
 */
@Composable
private fun TeamTableTab(
    profile: TeamProfile,
    table: LeagueTableUiState,
    onVenue: (TableVenue) -> Unit,
    onOpenFullTable: () -> Unit,
    onTeamClick: (String) -> Unit,
) {
    val ownRow = table.rows.firstOrNull { it.teamId == profile.team.id }
        ?: table.rows.filter {
            com.footballai.app.data.remote.ClubNameMatcher.matches(profile.team.name, it.teamName)
        }.singleOrNull()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = Spacing.page, vertical = Spacing.sm)) {
                if (table.isComputed) TableProvenanceNote(matches = table.matches)
                if (table.canSplitByVenue) {
                    VenueChips(selected = table.venue, onSelect = onVenue)
                }
            }
        }
        if (table.isEmpty) {
            item {
                LeagueTableEmpty(
                    isKnockout = table.isKnockout,
                    onRequest = onOpenFullTable,
                )
            }
        } else if (ownRow == null) {
            item {
                EmptyState(
                    title = stringResource(R.string.table_team_missing_title),
                    message = stringResource(R.string.table_team_missing_message),
                    actionLabel = stringResource(R.string.table_open_full_page),
                    onAction = onOpenFullTable,
                )
            }
        } else {
            item {
                LeagueTableBlock(
                    rows = table.rows,
                    modifier = Modifier.padding(horizontal = Spacing.page),
                    highlightTeamIds = setOf(ownRow.teamId),
                    // Tapping the club whose page this is would only push a copy of itself.
                    onTeamClick = { teamId -> if (teamId != ownRow.teamId) onTeamClick(teamId) },
                )
            }
            item {
                SoftCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.page, vertical = Spacing.cardGap),
                    onClick = onOpenFullTable,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.TableChart,
                            contentDescription = null,
                            tint = FootballAiThemeExtra.colors.iconMuted,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.table_open_full_page),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingCard(label: String, value: String, hint: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FormDot(outcome: ResultOutcome) {
    val colors = FootballAiThemeExtra.colors
    val color = when (outcome) {
        ResultOutcome.WIN -> colors.positive
        ResultOutcome.DRAW -> MaterialTheme.colorScheme.onSurfaceVariant
        ResultOutcome.LOSS -> colors.danger
    }
    // A tick, a bar and a cross instead of W/D/L: the letters were Latin inside a Persian
    // screen, and a single Latin letter in a coloured dot reads as a foreign word.
    val label = when (outcome) {
        ResultOutcome.WIN -> stringResource(R.string.form_win)
        ResultOutcome.DRAW -> stringResource(R.string.form_draw)
        ResultOutcome.LOSS -> stringResource(R.string.form_loss)
    }
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        when (outcome) {
            ResultOutcome.WIN, ResultOutcome.LOSS -> Icon(
                imageVector = if (outcome == ResultOutcome.WIN) Icons.Default.Check else Icons.Default.Close,
                contentDescription = label,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(13.dp),
            )
            ResultOutcome.DRAW -> androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .width(9.dp)
                    .height(2.dp)
                    .background(androidx.compose.ui.graphics.Color.White, androidx.compose.foundation.shape.RoundedCornerShape(1.dp)),
            ) { }
        }
    }
}

@Composable
private fun ResultRow(result: TeamResult, languageTag: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TeamNameLines(
                    team = result.opponent,
                    languageTag = languageTag,
                    primaryStyle = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = Formatters.dateOnly(result.kickoffEpochMs, languageTag),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChip(
                text = if (result.wasHome) stringResource(R.string.home_short) else stringResource(R.string.away_short),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = Formatters.localizeDigits("${result.goalsFor} - ${result.goalsAgainst}", languageTag),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
