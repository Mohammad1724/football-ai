package com.footballai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.components.CompetitionNameText
import com.footballai.app.ui.components.EmptyState
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.viewmodel.CompetitionOrderViewModel

/**
 * Puts the competitions in the order the user wants them.
 *
 * Up and down rather than drag and drop: a list that reorders itself under a finger is
 * easy to get wrong, and two buttons are exact on any screen size. Every press is saved
 * immediately, so leaving the screen never loses the arrangement.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompetitionOrderScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel: CompetitionOrderViewModel = viewModel(
        factory = viewModelFactory { initializer { CompetitionOrderViewModel(container) } },
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
                title = { Text(stringResource(R.string.title_competition_order)) },
                actions = {
                    if (state.hasCustomOrder) {
                        IconButton(onClick = viewModel::reset) {
                            Icon(Icons.Default.RestartAlt, contentDescription = stringResource(R.string.action_reset_order))
                        }
                    }
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
            if (state.competitions.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.empty_competition_order_title),
                    message = stringResource(R.string.empty_competition_order_message),
                )
                return@Column
            }
            Text(
                text = stringResource(R.string.competition_order_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(state.competitions, key = { _, competition -> competition.id }) { index, competition ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            CompetitionNameText(
                                name = competition.name,
                                competitionId = competition.id,
                                languageTag = languageTag,
                                primaryStyle = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = listOfNotNull(
                                    competition.country?.takeIf { it.isNotBlank() },
                                    competition.providerId.takeIf { it.isNotBlank() },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { viewModel.move(competition.id, up = true) },
                            enabled = index > 0,
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.action_move_up))
                        }
                        IconButton(
                            onClick = { viewModel.move(competition.id, up = false) },
                            enabled = index < state.competitions.lastIndex,
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.action_move_down))
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = viewModel::reset, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.action_reset_order))
                    }
                    Spacer(Modifier.width(4.dp))
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
