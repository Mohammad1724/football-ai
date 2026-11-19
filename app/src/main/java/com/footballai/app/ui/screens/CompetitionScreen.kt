package com.footballai.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.footballai.app.R
import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.components.SoftBackButton
import com.footballai.app.ui.components.EmptyState
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.components.MatchCard
import com.footballai.app.ui.components.SoftCard
import com.footballai.app.ui.viewmodel.CompetitionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompetitionScreen(
    competitionId: String,
    onBack: () -> Unit,
    onMatchClick: (String) -> Unit,
    onTableClick: () -> Unit,
) {
    val container = LocalAppContainer.current
    val viewModel: CompetitionViewModel = viewModel(
        key = competitionId,
        factory = viewModelFactory { initializer { CompetitionViewModel(container, competitionId) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val languageTag = LocalLanguageTag.current

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
                    val competition = state.competition
                    Text(
                        competition?.let {
                            NameLocalization.competitionNamePair(it.name, it.id, languageTag).first
                        } ?: stringResource(R.string.title_competition),
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
            // Same bottom inset as the other lists: without it the last card sits under the
            // gesture bar and looks cut off.
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                // The table is the reason most readers open a competition, so it is the first
                // thing on the page — and it stays reachable when the fixtures list is empty.
                item(key = "competition-table-entry") {
                    SoftCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        onClick = onTableClick,
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.table_entry_title),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = stringResource(R.string.table_entry_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (state.fixtures.isEmpty()) {
                    item(key = "competition-empty") {
                        EmptyState(
                            title = stringResource(R.string.empty_competition_title),
                            message = stringResource(R.string.empty_competition_message),
                        )
                    }
                } else {
                    items(state.fixtures, key = { it.id }) { match ->
                        MatchCard(
                            match = match,
                            languageTag = languageTag,
                            onClick = { onMatchClick(match.id) },
                        )
                    }
                }
            }
        }
    }
}
