@file:OptIn(ExperimentalFoundationApi::class)

package com.footballai.app.ui.screens

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.footballai.app.R
import com.footballai.app.domain.model.Competition
import com.footballai.app.ui.LocalAppContainer
import com.footballai.app.ui.LocalLanguageTag
import com.footballai.app.ui.components.SoftBackButton
import com.footballai.app.ui.components.CompetitionNameText
import com.footballai.app.ui.components.EmptyState
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.viewmodel.CompetitionOrderViewModel

/** Fastest auto-scroll while dragging. In pixels per second, so frame rate cannot change it. */
private const val MAX_SCROLL_PX_PER_SECOND = 1800f

/**
 * Speed the moment the row enters the edge band.
 *
 * Starting from zero meant the first few pixels of the band did nothing, which reads as a
 * scroll that only starts once the drag is already stuck at the border.
 */
private const val MIN_SCROLL_PX_PER_SECOND = 420f

/** Share of the screen, top and bottom, in which a drag scrolls the list by itself. */
private const val EDGE_BAND_FRACTION = 0.18f

/**
 * Puts the tournaments in the order the user wants them: La Liga above Serie A, Serie A
 * above the Bundesliga, whatever the app's own guess was.
 *
 * Reordering is done by the handle on the right of each row: press it, hold, and drag the
 * row up or down. The list follows the finger and is saved the moment it is dropped, so
 * there is no save button to forget. Long press before dragging is deliberate — without
 * it, every attempt to scroll the list would move a tournament.
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
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()

    // A local copy so a drag can move a row before it is saved. While a finger is on a
    // handle the stored list is ignored; otherwise a new emission would yank the row.
    val rows = remember { mutableStateListOf<Competition>() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    // Where the finger is, in pixels from the top of the list. Everything about the drag is
    // derived from this one number, so the row can never drift away from the finger: it is
    // not moved by accumulating deltas, it is placed at the finger on every frame.
    var pointerY by remember { mutableFloatStateOf(0f) }
    // The row's displacement from its own slot, recomputed every frame as
    // pointerY - slot centre. Never accumulated, so no rounding, no drift, no overshoot.
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // Pixels per second; sign is the direction. Zero means the finger is not near an edge.
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }
    val colors = FootballAiThemeExtra.colors

    LaunchedEffect(state.competitions) {
        if (draggingId == null) {
            rows.clear()
            rows.addAll(state.competitions)
        }
    }

    fun visibleItem(id: String) =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }

    fun viewportHeightPx(): Float = listState.layoutInfo.viewportSize.height.toFloat()

    /**
     * How far the finger may go: far enough that the row can reach the first and last slot,
     * not far enough for the row to leave the screen. This is the clamp that used to be
     * missing — the row was drawn wherever the accumulated offset pointed, which at the top
     * of the list was above the screen.
     */
    fun clampPointer(y: Float, viewport: Float): Float {
        val id = draggingId ?: return y
        val half = (visibleItem(id)?.size?.toFloat() ?: 76f) / 2f
        if (viewport <= half * 2f) return viewport / 2f
        return y.coerceIn(half, viewport - half)
    }

    /** Puts the dragged row exactly under the finger, using the layout on screen right now. */
    fun placeRowUnderFinger() {
        val id = draggingId ?: return
        val item = visibleItem(id) ?: return
        dragOffset = pointerY - (item.offset + item.size / 2f)
    }

    /**
     * Puts the dragged row into whichever slot the finger is over.
     *
     * The rule is pure geometry: find the row whose on-screen bounds contain the finger and
     * take its index. Nothing is accumulated and no row height is guessed, so it cannot
     * drift, and because the dragged row itself is left out of the search, the same slot
     * cannot pull it back and forth — the row settles the moment it is where the finger is.
     * This also keeps working while the list scrolls by itself: the finger stays over
     * whichever slot is passing under it, and the row travels with it.
     */
    fun maybeReorder() {
        val id = draggingId ?: return
        val from = rows.indexOfFirst { it.id == id }
        if (from < 0) return
        val candidates = listState.layoutInfo.visibleItemsInfo.filter { item ->
            item.key != id && item.index in rows.indices
        }
        if (candidates.isEmpty()) return
        val target = candidates.firstOrNull { pointerY >= it.offset && pointerY < it.offset + it.size }
            // Above the first row on screen, or below the last one (the reset button and the
            // spacer live there), the drag still means "to the end of the list".
            ?: if (pointerY < candidates.first().offset) {
                candidates.first()
            } else if (pointerY >= candidates.last().offset + candidates.last().size) {
                candidates.last()
            } else {
                return
            }
        if (target.index == from) return
        val moved = rows.removeAt(from)
        rows.add(target.index, moved)
        // The row now sits in the slot the finger is over; measure its offset from that
        // slot, or the frame that draws the swap is a whole row out of place.
        dragOffset = pointerY - (target.offset + target.size / 2f)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /**
     * Scroll speed for where the finger is: zero in the middle of the screen, growing
     * towards [MAX_SCROLL_PX_PER_SECOND] inside the edge band.
     */
    fun updateAutoScroll(viewport: Float) {
        if (draggingId == null || viewport <= 0f) {
            autoScrollSpeed = 0f
            return
        }
        val edge = (viewport * EDGE_BAND_FRACTION).coerceAtLeast(1f)
        fun speedFor(depth: Float): Float {
            val t = (depth / edge).coerceIn(0f, 1f)
            return MIN_SCROLL_PX_PER_SECOND +
                t * (MAX_SCROLL_PX_PER_SECOND - MIN_SCROLL_PX_PER_SECOND)
        }
        autoScrollSpeed = when {
            pointerY < edge -> -speedFor(edge - pointerY)
            pointerY > viewport - edge -> speedFor(pointerY - (viewport - edge))
            else -> 0f
        }
    }

    // One frame at a time: place the row where the layout says it belongs, swap if the finger
    // has passed a neighbour, then scroll the list if the finger is in an edge band. The
    // scroll comes last so the next frame reads a layout that already includes it.
    LaunchedEffect(draggingId) {
        var lastFrame = 0L
        while (draggingId != null) {
            val frame = withFrameNanos { it }
            val dt = if (lastFrame == 0L) {
                1f / 60f
            } else {
                ((frame - lastFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
            }
            lastFrame = frame
            placeRowUnderFinger()
            maybeReorder()
            val viewport = viewportHeightPx()
            updateAutoScroll(viewport)
            val speed = autoScrollSpeed
            if (speed != 0f) {
                val consumed = listState.scrollBy(speed * dt)
                // The list moved the row's slot by this much; move the row the other way so
                // it still sits under the finger in the frame that is about to be drawn.
                dragOffset += consumed
            }
        }
    }

    fun drop() {
        draggingId = null
        dragOffset = 0f
        autoScrollSpeed = 0f
        viewModel.applyOrder(rows.map { it.id })
    }

    fun drag(deltaY: Float) {
        if (draggingId == null) return
        val viewport = viewportHeightPx()
        pointerY = clampPointer(pointerY + deltaY, viewport)
        placeRowUnderFinger()
        maybeReorder()
        updateAutoScroll(viewport)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    SoftBackButton(
                        onClick = onBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
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
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(rows, key = { it.id }) { competition ->
                    val isDragging = competition.id == draggingId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Rows that are pushed aside glide to their new place; the row
                            // under the finger must not, it already follows the finger.
                            .then(if (isDragging) Modifier else Modifier.animateItemPlacement(tween(130)))
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                            .background(if (isDragging) colors.cardStrong else Color.Transparent)
                            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
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
                        Icon(
                            imageVector = Icons.Default.DragHandle,
                            contentDescription = stringResource(R.string.action_reorder),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 14.dp)
                                .size(26.dp)
                                .pointerInput(competition.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggingId = competition.id
                                            dragOffset = 0f
                                            pointerY = listState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.key == competition.id }
                                                ?.let { it.offset + it.size / 2f }
                                                ?: 0f
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            drag(dragAmount.y)
                                        },
                                        onDragEnd = { drop() },
                                        onDragCancel = { drop() },
                                    )
                                },
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = viewModel::reset, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.action_reset_order))
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
