package com.footballai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.footballai.app.R
import com.footballai.app.core.Formatters
import com.footballai.app.domain.model.ResultOutcome
import com.footballai.app.domain.model.TeamResult
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.components.EmptyState
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.components.SectionHeader
import com.footballai.app.ui.components.StatusChip
import com.footballai.app.ui.components.TeamMonogram
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.viewmodel.TeamViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamScreen(teamId: String, onBack: () -> Unit) {
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
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(profile?.team?.name ?: stringResource(R.string.title_team)) },
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
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TeamMonogram(profile.team.name, size = 52)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = profile.team.name,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = profile.competitionName.ifBlank { stringResource(R.string.unknown_competition) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            text = stringResource(
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

@Composable
private fun RatingCard(label: String, value: String, hint: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
    val letter = when (outcome) {
        ResultOutcome.WIN -> "W"
        ResultOutcome.DRAW -> "D"
        ResultOutcome.LOSS -> "L"
    }
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            style = MaterialTheme.typography.labelSmall,
            color = androidx.compose.ui.graphics.Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ResultRow(result: TeamResult, languageTag: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = result.opponent.name, style = MaterialTheme.typography.bodyMedium)
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
