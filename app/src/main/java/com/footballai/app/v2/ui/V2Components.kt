package com.footballai.app.v2.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.footballai.app.core.Formatters
import com.footballai.app.v2.V2Dates
import com.footballai.app.v2.data.V2FixtureDto
import com.footballai.app.v2.data.V2LiveMatchDto
import com.footballai.app.v2.theme.LocalV2Palette

private const val LRM = "\u200E"

/**
 * A team name on one line. Latin (LTR) names inside RTL rows get an invisible
 * left-to-right mark on BOTH sides: a prefix alone does not hold a trailing
 * neutral character, and without the marks punctuation and digits jump to
 * the wrong end of the name.
 */
@Composable
fun TeamNameText(
    name: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    val palette = LocalV2Palette.current
    val wrapped = if (name.any { it in 'a'..'z' || it in 'A'..'Z' }) "$LRM$name$LRM" else name
    Text(
        text = wrapped,
        modifier = modifier,
        color = palette.ink,
        fontSize = 13.5.sp,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Slim top bar shared by the three tabs: screen title + settings gear. */
@Composable
fun V2TopBar(
    title: String,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalV2Palette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = palette.ink,
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        IconButton(onClick = onSettingsClick, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "تنظیمات",
                tint = palette.inkMuted,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * One match row inside a league card: home team, a fixed centre slot
 * (kickoff time / live minute + score / final score), away team.
 *
 * The live overlay from live.json wins over the bundle's own fields — it is
 * regenerated every few minutes while the bundle is daily.
 */
@Composable
fun V2MatchRow(
    fixture: V2FixtureDto,
    live: V2LiveMatchDto?,
    modifier: Modifier = Modifier,
) {
    val palette = LocalV2Palette.current
    val status = live?.status ?: fixture.status
    val homeScore = live?.homeScore ?: fixture.homeScore
    val awayScore = live?.awayScore ?: fixture.awayScore
    val minute = live?.minute ?: fixture.minute

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamNameText(
            name = fixture.home.displayName,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Start,
            fontWeight = if (status == "finished") FontWeight.SemiBold else FontWeight.Normal,
        )
        Box(
            modifier = Modifier.width(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (status) {
                "live" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = Formatters.localizeDigits(minute ?: "زنده", "fa"),
                        color = palette.live,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = scoreText(homeScore, awayScore),
                        color = palette.ink,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                "finished" -> Text(
                    text = scoreText(homeScore, awayScore),
                    color = palette.ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                "postponed" -> Text(
                    text = "تعویق",
                    color = palette.inkMuted,
                    fontSize = 11.5.sp,
                )
                "cancelled" -> Text(
                    text = "لغو شد",
                    color = palette.inkMuted,
                    fontSize = 11.5.sp,
                )
                else -> {
                    val kickoff = V2Dates.parseInstant(fixture.kickoffUtc)
                    Text(
                        text = kickoff?.let { V2Dates.timeFa(it) } ?: "—",
                        color = palette.inkMuted,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        TeamNameText(
            name = fixture.away.displayName,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            fontWeight = if (status == "finished") FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private fun scoreText(home: Int?, away: Int?): String {
    if (home == null || away == null) return "—"
    return Formatters.localizeDigits("$home - $away", "fa")
}

/** Vertical gap used between the league cards on the home feed. */
val V2CardGap = 12.dp

/** Bottom inset so list content is never hidden behind the floating bar. */
val V2FloatingBarInset = 116.dp

@Composable
fun V2SectionLabel(text: String, modifier: Modifier = Modifier) {
    val palette = LocalV2Palette.current
    Text(
        text = text,
        color = palette.inkMuted,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Composable
fun V2EmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalV2Palette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            color = palette.ink,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = subtitle,
            color = palette.inkMuted,
            fontSize = 12.5.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
