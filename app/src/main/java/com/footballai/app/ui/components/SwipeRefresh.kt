package com.footballai.app.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.footballai.app.R
import com.footballai.app.core.AppError
import com.footballai.app.core.Formatters
import com.footballai.app.ui.errorLabel
import com.footballai.app.ui.relativeLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Pull down to refresh", implemented on the nested-scroll protocol.
 *
 * The reason this is written by hand instead of pulled from a library is the same reason
 * the rest of the app is: one behaviour, no surprises. The gesture only starts when the
 * content has nothing left to scroll (so a list scrolled to the middle still scrolls
 * normally), it never fires while a refresh is already running, and the indicator only
 * appears for a gesture the user actually made — an automatic sync shows the status strip
 * instead, because pinning a spinner on a screen the user just opened looks broken.
 *
 * A screen whose content is not scrollable (an empty state) must wrap it in
 * [ScrollableRefreshContent], otherwise there is no scroll to pull.
 */
@Composable
fun SwipeRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val thresholdPx = with(density) { 72.dp.toPx() }
    val maxPullPx = with(density) { 140.dp.toPx() }
    val indicatorSizePx = with(density) { 40.dp.toPx() }

    // A plain state, not Animatable: in this Compose version the nested-scroll drag
    // callbacks are not suspend, so the offset has to be writable synchronously to stay
    // glued to the finger. Settling is animated explicitly.
    var pull by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    val enabledState = rememberUpdatedState(enabled)
    val refreshingState = rememberUpdatedState(isRefreshing)
    // Set only when this gesture asked for the refresh, so an automatic sync never pins
    // the indicator.
    var pullTriggered by remember { mutableStateOf(false) }

    fun settle(target: Float) {
        scope.launch {
            animate(initialValue = pull, targetValue = target) { value, _ -> pull = value }
        }
    }

    LaunchedEffect(isRefreshing) {
        if (isRefreshing && pullTriggered) {
            pull = thresholdPx
        } else if (!isRefreshing) {
            pullTriggered = false
            if (pull > 0f) settle(0f)
        }
    }

    val connection = remember(thresholdPx, maxPullPx) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source != NestedScrollSource.Drag) return Offset.Zero
                // Only leftover movement reaches here: the content is already at its top,
                // so this pull can never be confused with a normal scroll.
                if (available.y < 0f && pull > 0f) {
                    // Dragging back up collapses the indicator first.
                    pull = (pull + available.y).coerceAtLeast(0f)
                    return Offset(0f, available.y)
                }
                if (available.y <= 0f) return Offset.Zero
                if (!enabledState.value || refreshingState.value) return Offset.Zero
                pull = (pull + available.y * 0.5f).coerceIn(0f, maxPullPx)
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull <= 0f) return Velocity.Zero
                val shouldRefresh = pull >= thresholdPx &&
                    enabledState.value &&
                    !refreshingState.value
                if (shouldRefresh) {
                    pullTriggered = true
                    pull = thresholdPx
                    onRefresh()
                    // If nothing actually started (the screen refused, or the sync ended
                    // before it began), the indicator must not stay pinned forever.
                    scope.launch {
                        delay(2_000L)
                        if (!refreshingState.value) {
                            pullTriggered = false
                            settle(0f)
                        }
                    }
                } else {
                    animate(initialValue = pull, targetValue = 0f) { value, _ -> pull = value }
                }
                return Velocity(0f, available.y)
            }
        }
    }

    val indicatorVisible by remember { derivedStateOf { pull > 0f } }

    Box(modifier = modifier.nestedScroll(connection)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = pull },
            content = content,
        )
        if (indicatorVisible) {
            val indicatorColor = MaterialTheme.colorScheme.surfaceVariant
            val iconColor = MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer { translationY = pull - indicatorSizePx - 8f }
                    .size(40.dp)
                    .background(indicatorColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = (pull / thresholdPx).coerceIn(0f, 1f) * 240f },
                        tint = iconColor,
                    )
                }
            }
        }
    }
}

/** Content that cannot scroll still needs a scroll for the pull gesture to reach it. */
@Composable
fun ScrollableRefreshContent(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        content = content,
    )
}

/**
 * One line that answers "is what I am looking at current?".
 *
 * Three states, never mixed: offline, refreshing (with progress when the source reports
 * it), or refreshed at a time the user can judge. A failure of an automatic sync appears
 * here too — otherwise the app would silently keep showing yesterday's fixtures.
 */
@Composable
fun SyncStatusStrip(
    isSyncing: Boolean,
    isAuto: Boolean,
    progressDone: Int,
    progressTotal: Int,
    lastSyncEpochMs: Long?,
    nowEpochMs: Long,
    added: Int?,
    error: AppError?,
    isOnline: Boolean,
    languageTag: String,
    modifier: Modifier = Modifier,
) {
    if (!isOnline) {
        OfflineBanner(modifier = modifier)
        return
    }
    val refreshing = isSyncing || isAuto
    if (!refreshing && error == null && lastSyncEpochMs == null) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            if (refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (progressTotal > 0) {
                        stringResource(
                            R.string.sync_progress_label,
                            Formatters.integer(progressDone, languageTag),
                            Formatters.integer(progressTotal, languageTag),
                        )
                    } else {
                        stringResource(R.string.sync_running_label)
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            } else if (error != null && (isAuto || added == null)) {
                Text(
                    text = errorLabel(error),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (lastSyncEpochMs != null) {
                val relative = Formatters.relative(lastSyncEpochMs, nowEpochMs, languageTag)
                val whenLabel = relativeLabel(relative, isPast = true)
                val suffix = if (added != null && added > 0) {
                    " • " + stringResource(
                        R.string.sync_added_suffix,
                        Formatters.integer(added, languageTag),
                    )
                } else {
                    ""
                }
                Text(
                    text = stringResource(
                        R.string.sync_last_updated,
                        whenLabel,
                    ) + suffix,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
