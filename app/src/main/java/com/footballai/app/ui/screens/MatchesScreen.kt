package com.footballai.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.footballai.app.R
import com.footballai.app.domain.model.Competition
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.components.EmptyState
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.components.MatchCard
import com.footballai.app.ui.viewmodel.MatchListFilter
import com.footballai.app.ui.viewmodel.MatchesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchesScreen(onMatchClick: (String) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: MatchesViewModel = viewModel(
        factory = viewModelFactory { initializer { MatchesViewModel(container) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val languageTag = LocalLanguageTag.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_matches)) },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_sync))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MatchListFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = {
                            Text(
                                stringResource(
                                    when (filter) {
                                        MatchListFilter.TODAY -> R.string.filter_today
                                        MatchListFilter.TOMORROW -> R.string.filter_tomorrow
                                        MatchListFilter.WEEK -> R.string.filter_week
                                        MatchListFilter.FINISHED -> R.string.filter_finished
                                    },
                                ),
                            )
                        },
                    )
                }
            }
            if (state.competitions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.competitionId == null,
                        onClick = { viewModel.setCompetition(null) },
                        label = { Text(stringResource(R.string.filter_all_competitions)) },
                    )
                    state.competitions.take(12).forEach { competition: Competition ->
                        FilterChip(
                            selected = state.competitionId == competition.id,
                            onClick = { viewModel.setCompetition(competition.id) },
                            label = { Text(competition.name) },
                        )
                    }
                }
            }

            if (state.isLoading) {
                LoadingState()
            } else if (state.matches.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.empty_matches_title),
                    message = stringResource(R.string.empty_matches_message),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.matches, key = { it.id }) { match ->
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
