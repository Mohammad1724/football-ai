package com.footballai.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.footballai.app.R
import com.footballai.app.data.repository.DailyAnalysisPolicy
import com.footballai.app.domain.model.PredictionView
import com.footballai.app.domain.model.TeamRef
import com.footballai.app.domain.catalog.NameLocalization

@Composable
fun PreMatchVerdict(prediction: PredictionView, home: TeamRef, away: TeamRef, languageTag: String) {
    val pick = DailyAnalysisPolicy.pick(prediction.homeWin, prediction.draw, prediction.awayWin, prediction.quality.level)
    val label = when (pick) {
        "HOME" -> stringResource(R.string.daily_pick_team, NameLocalization.displayName(home.name, languageTag, home.shortName))
        "AWAY" -> stringResource(R.string.daily_pick_team, NameLocalization.displayName(away.name, languageTag, away.shortName))
        "DRAW" -> stringResource(R.string.daily_pick_draw)
        "INSUFFICIENT" -> stringResource(R.string.error_insufficient_history)
        else -> stringResource(R.string.daily_pick_unclear)
    }
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        val team = when (pick) { "HOME" -> home; "AWAY" -> away; else -> null }
        val secondary = team?.let { NameLocalization.namePair(it.name, languageTag, it.shortName).second }
        if (languageTag.startsWith("fa", ignoreCase = true) && secondary != null) {
            Text("\u200E" + secondary, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.daily_pick_note_short), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
