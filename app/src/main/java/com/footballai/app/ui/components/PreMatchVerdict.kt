package com.footballai.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.footballai.app.R
import com.footballai.app.data.repository.DailyAnalysisPolicy
import com.footballai.app.domain.model.PredictionView

@Composable
fun PreMatchVerdict(prediction: PredictionView, home: String, away: String) {
    val pick = DailyAnalysisPolicy.pick(prediction.homeWin, prediction.draw, prediction.awayWin, prediction.quality.level)
    val label = when (pick) {
        "HOME" -> stringResource(R.string.daily_pick_team, home)
        "AWAY" -> stringResource(R.string.daily_pick_team, away)
        "DRAW" -> stringResource(R.string.daily_pick_draw)
        "INSUFFICIENT" -> stringResource(R.string.error_insufficient_history)
        else -> stringResource(R.string.daily_pick_unclear)
    }
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.daily_pick_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
