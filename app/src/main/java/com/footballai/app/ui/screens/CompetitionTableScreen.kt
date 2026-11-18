package com.footballai.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.footballai.app.R
import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.domain.model.TeamRef
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.components.EmptyState
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.components.SoftBackButton
import com.footballai.app.ui.components.SoftCard
import com.footballai.app.ui.components.TeamBadge
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.theme.Spacing
import com.footballai.app.ui.viewmodel.CompetitionTableViewModel
import com.footballai.app.ui.viewmodel.TableRow

/**
 * The table of one tournament.
 *
 * Reached by tapping the pinned tournament name in the fixtures list, which is where a
 * reader already is when the question "who is above whom" comes up. Every row shows the
 * club's crest (or its initials), the played count, goal difference and points — the four
 * columns a table is read by — and the ranking number is drawn the way the language reads,
 * so it stays on the right in Persian.
 */
@Composable
fun CompetitionTableScreen(
    competitionId: String,
    onBack: () -> Unit,
    onTeamClick: (String) -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel: CompetitionTableViewModel = viewModel(
        key = competitionId,
        factory = viewModelFactory { initializer { CompetitionTableViewModel(container, competitionId) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val languageTag = LocalLanguageTag.current
    val competitionName = NameLocalization
        .competitionNamePair(state.competitionName, competitionId, languageTag).first

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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = competitionName.ifBlank { stringResource(R.string.title_competition) },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        state.season?.let { season ->
                            Text(
                                text = seasonLabel(season),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> LoadingState()
                state.rows.isEmpty() -> EmptyState(
                    title = stringResource(R.string.empty_table_title),
                    message = stringResource(R.string.empty_table_message),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Spacing.page, end = Spacing.page,
                        top = Spacing.sm, bottom = Spacing.xxl,
                    ),
                ) {
                    item(key = "table-header") { TableHeader() }
                    items(state.rows, key = { it.teamId }) { row ->
                        TableRowCard(row = row, onClick = { onTeamClick(row.teamId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.table_played),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(28.dp),
        )
        Text(
            text = stringResource(R.string.table_goal_difference),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(28.dp),
        )
        Text(
            text = stringResource(R.string.table_points),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(32.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = stringResource(R.string.table_team),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TableRowCard(row: TableRow, onClick: () -> Unit) {
    val colors = FootballAiThemeExtra.colors
    val languageTag = LocalLanguageTag.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxs)) {
        SoftCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
            containerColor = if (row.rank <= 3) colors.cardStrong else colors.card,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NumberCell(value = row.played.toString(), width = 28)
                NumberCell(
                    value = if (row.goalDifference > 0) "+${row.goalDifference}" else row.goalDifference.toString(),
                    width = 28,
                )
                NumberCell(value = row.points.toString(), width = 32, bold = true)
                Spacer(Modifier.width(Spacing.sm))
                TeamBadge(
                    team = TeamRef(id = row.teamId, name = row.teamName, crestUrl = row.crestUrl),
                    size = 24,
                )
                Spacer(Modifier.width(Spacing.sm))
                // The Persian name when the catalog has one, the published name otherwise.
                Text(
                    text = NameLocalization.namePair(row.teamName, languageTag).first,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = row.rank.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun NumberCell(value: String, width: Int, bold: Boolean = false) {
    Text(
        text = value,
        style = if (bold) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(width.dp),
    )
}

/** A season is written as a single year or a span, the way the source numbers it. */
private fun seasonLabel(season: Int): String =
    if (season >= 2000) "${season - 1}/${season.toString().takeLast(2)}" else season.toString()
