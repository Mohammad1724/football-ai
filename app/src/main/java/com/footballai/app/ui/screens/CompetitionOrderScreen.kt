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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.abs

/** Fastest auto-scroll while dragging. In pixels per second, so frame rate cannot change it. */
private const val MAX_SCROLL_PX_PER_SECOND = 1800f

/**
 * Speed the moment the row enters the edge band.
 *
 * Starting from zero meant the first few pixels of the band did nothing, which reads as a
 * scroll that only starts once the drag is already stuck at the border.
 */
private const val MIN_SCROLL_PX_PER_SECOND = 420f

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
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeights = remember { mutableStateMapOf<String, Int>() }
    // Pixels per second; sign is the direction. Zero means the row is not near an edge.
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }
    val colors = FootballAiThemeExtra.colors
    val density = LocalDensity.current
    // Used only until the row has been measured once.
    val defaultRowHeightPx = with(density) { 76.dp.roundToPx() }
    // How far a row may hang past the first or last slot before it is pulled back.
    val rubberBandPx = with(density) { 10.dp.toPx() }

    LaunchedEffect(state.competitions) {
        if (draggingId == null) {
            rows.clear()
            rows.addAll(state.competitions)
        }
    }

    /**
     * Height of a row, from the layout that is on screen right now.
     *
     * The map filled by onGloballyPositioned arrives a frame late and is empty for rows
     * that have never been composed; bailing out on a missing height is why a drag used to
     * stop travelling as soon as it reached a part of the list that had not been measured.
     */
    fun rowHeightPx(id: String): Int =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.size
            ?: rowHeights[id]
            ?: defaultRowHeightPx

    /**
     * Swap the dragged row with its neighbour as often as the offset has earned.
     *
     * This is deliberately callable without a new touch event: while the list scrolls by
     * itself the offset keeps growing even though the finger is still, and the swaps have
     * to happen anyway — that is what makes a row travel instead of hanging at the edge.
     */
    fun settle() {
        val id = draggingId ?: return
        var guard = 0
        while (guard++ < 12) {
            val from = rows.indexOfFirst { it.id == id }
            if (from < 0) return
            val step = if (dragOffset > 0f) 1 else -1
            val neighbour = rows.getOrNull(from + step)
            if (neighbour == null) {
                // First or last slot: the row may hang a few pixels past it and no more.
                // Holding the offset at zero felt like a snap; letting it grow is what sent
                // the row off the top of the screen once the list had nothing left to
                // scroll and stopped feeding the offset.
                dragOffset = dragOffset.coerceIn(-rubberBandPx, rubberBandPx)
                return
            }
            val height = rowHeightPx(neighbour.id)
            if (abs(dragOffset) <= height / 2f) return
            val moved = rows.removeAt(from)
            rows.add(from + step, moved)
            dragOffset -= step * height
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    /**
     * Scroll speed for where the dragged row currently sits: zero in the middle of the
     * list, growing towards [MAX_SCROLL_PX_PER_SECOND] as it enters the edge band.
     *
     * Speed by distance rather than a fixed step, because a constant speed feels like the
     * list is fighting the hand.
     */
    fun updateAutoScroll() {
        val id = draggingId ?: return
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
        val viewport = listState.layoutInfo.viewportSize.height
        if (item == null || viewport <= 0) {
            autoScrollSpeed = 0f
            return
        }
        val rowY = item.offset + dragOffset + item.size / 2f
        val edge = (viewport * 0.20f).coerceAtLeast(1f)
        fun speedFor(depth: Float): Float {
            val t = (depth / edge).coerceIn(0f, 1f)
            return MIN_SCROLL_PX_PER_SECOND +
                t * (MAX_SCROLL_PX_PER_SECOND - MIN_SCROLL_PX_PER_SECOND)
        }
        autoScrollSpeed = when {
            rowY < edge -> -speedFor(edge - rowY)
            rowY > viewport - edge -> speedFor(rowY - (viewport - edge))
            else -> 0f
        }
    }

    // The list scrolls itself while the row rests near an edge, one frame at a time. Two
    // details make it behave: the speed is re-evaluated every frame (it was only computed
    // on touch events, so it froze as soon as the finger held still), and the scrolled
    // distance is added back to the row's offset so the row stays under the finger instead
    // of sliding away with the content.
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
            updateAutoScroll()
            val speed = autoScrollSpeed
            if (speed != 0f) {
                val consumed = listState.scrollBy(speed * dt)
                // When the list has no room left to scroll, the same motion goes into the
                // row instead. Without this the drag stalls the moment the list reaches its
                // first or last item, with items still left to pass.
                dragOffset += if (consumed != 0f) consumed else speed * dt
            }
            // Every frame, not only when the list moved: at the top of the list nothing
            // scrolls any more, and that is exactly when the row has to be pulled back to
            // its first slot.
            settle()
        }
    }

    fun drop() {
        draggingId = null
        dragOffset = 0f
        autoScrollSpeed = 0f
        viewModel.applyOrder(rows.map { it.id })
    }

    fun drag(amount: Float) {
        if (draggingId == null) return
        dragOffset += amount
        settle()
        updateAutoScroll()
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
                            .onGloballyPositioned { rowHeights[competition.id] = it.size.height }
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
