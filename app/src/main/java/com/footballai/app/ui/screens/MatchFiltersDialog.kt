package com.footballai.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.footballai.app.R
import com.footballai.app.domain.catalog.NameLocalization
import com.footballai.app.domain.model.Competition
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.viewmodel.MatchFilters
import com.footballai.app.ui.viewmodel.MatchGenderFilter
import com.footballai.app.ui.viewmodel.MatchKindFilter

/** Draft changes are applied together, or discarded when the dialog is dismissed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MatchFiltersDialog(
    initial: MatchFilters,
    competitions: List<Competition>,
    onApply: (MatchFilters) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var leagueMenuOpen by remember { mutableStateOf(false) }
    val languageTag = LocalLanguageTag.current
    // A Dialog creates another Android window. Capture the app-localized resources before
    // entering it rather than accidentally falling back to the phone's system language.
    val resources = LocalContext.current.resources
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.82f).dp
    val selected = competitions.firstOrNull { it.id == draft.competitionId }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).heightIn(max = maxHeight),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(resources.getString(R.string.match_filters_title),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = resources.getString(R.string.action_close))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterSectionTitle(resources.getString(R.string.match_filters_competition))
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(onClick = { leagueMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = selected?.let { NameLocalization.competitionNamePair(it.name, it.id, languageTag).first }
                                    ?: resources.getString(R.string.filter_all_competitions),
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = leagueMenuOpen, onDismissRequest = { leagueMenuOpen = false }) {
                            DropdownMenuItem(text = { Text(resources.getString(R.string.filter_all_competitions)) },
                                onClick = { draft = draft.copy(competitionId = null); leagueMenuOpen = false })
                            competitions.forEach { competition ->
                                DropdownMenuItem(
                                    text = { Text(NameLocalization.competitionNamePair(competition.name, competition.id, languageTag).first) },
                                    onClick = { draft = draft.copy(competitionId = competition.id); leagueMenuOpen = false },
                                )
                            }
                        }
                    }
                    FilterSectionTitle(resources.getString(R.string.match_filters_kind))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MatchKindFilter.entries.forEach { kind ->
                            val label = when (kind) {
                                MatchKindFilter.ALL -> R.string.filter_all
                                MatchKindFilter.CLUBS -> R.string.filter_clubs
                                MatchKindFilter.NATIONAL -> R.string.filter_national
                            }
                            MatchChoiceChip(selected = draft.kind == kind, onClick = { draft = draft.copy(kind = kind) },
                                label = resources.getString(label))
                        }
                    }
                    FilterSectionTitle(resources.getString(R.string.match_filters_gender))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MatchGenderFilter.entries.forEach { gender ->
                            val label = when (gender) {
                                MatchGenderFilter.ALL -> R.string.filter_all
                                MatchGenderFilter.MEN -> R.string.filter_men
                                MatchGenderFilter.WOMEN -> R.string.filter_women
                            }
                            MatchChoiceChip(selected = draft.gender == gender, onClick = { draft = draft.copy(gender = gender) },
                                label = resources.getString(label))
                        }
                    }
                    FilterSectionTitle(resources.getString(R.string.match_filters_more))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MatchChoiceChip(selected = draft.followedOnly, onClick = { draft = draft.copy(followedOnly = !draft.followedOnly) },
                            label = resources.getString(R.string.match_filter_followed))
                        MatchChoiceChip(selected = draft.liveOnly, onClick = { draft = draft.copy(liveOnly = !draft.liveOnly) },
                            label = resources.getString(R.string.match_filter_live))
                    }
                    Text(resources.getString(R.string.match_filter_followed_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { draft = MatchFilters() }, enabled = draft.activeCount > 0) {
                        Text(resources.getString(R.string.match_filters_reset))
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onApply(draft); onDismiss() }) {
                        Text(resources.getString(R.string.match_filters_apply))
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp))
}

/** Selection has both a check mark and a high-contrast fill, not colour alone. */
@Composable
private fun MatchChoiceChip(selected: Boolean, onClick: () -> Unit, label: String) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        colors = matchFilterChipColors(),
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
        } else null,
    )
}

@Composable
internal fun matchFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)
