package com.footballai.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.footballai.app.R
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.components.EmptyState
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.components.MatchCard
import com.footballai.app.ui.viewmodel.CompetitionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompetitionScreen(
    competitionId: String,
    onBack: () -> Unit,
    onMatchClick: (String) -> Unit,
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
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(state.competition?.name ?: stringResource(R.string.title_competition)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> LoadingState()
                state.fixtures.isEmpty() -> EmptyState(
                    title = stringResource(R.string.empty_competition_title),
                    message = stringResource(R.string.empty_competition_message),
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
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
