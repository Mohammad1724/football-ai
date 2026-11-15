@file:OptIn(ExperimentalFoundationApi::class)

package com.footballai.app.ui.screens

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
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
import com.footballai.app.ui.components.CompetitionNameText
import com.footballai.app.ui.components.EmptyState
import com.footballai.app.ui.components.LoadingState
import com.footballai.app.ui.components.SoftBackButton
import com.footballai.app.ui.theme.FootballAiThemeExtra
import com.footballai.app.ui.viewmodel.CompetitionOrderViewModel
import kotlin.math.roundToInt

/** Fastest auto-scroll while dragging. In pixels per second, so frame rate cannot change it. */
private const val MAX_SCROLL_PX_PER_SECOND = 1800f

/**
 * Speed the moment the finger enters the edge band.
 *
 * Starting from zero meant the first pixels of the band did nothing, which reads as a scroll
 * that only begins once the drag is already stuck at the border.
 */
private const val MIN_SCROLL_PX_PER_SECOND = 420f

/** Share of the list height, top and bottom, in which a drag scrolls the list by itself. */
private const val EDGE_BAND_FRACTION = 0.18f

/**
 * Puts the tournaments in the order the user wants them: La Liga above Serie A, Serie A
 * above the Bundesliga, whatever the app's own guess was.
 *
 * Press and hold a row, then drag it up or down. The order is saved the moment it is
 * dropped, so there is no save button to forget. The long press is deliberate — without it
 * every attempt to scroll the list would move a tournament.
 *
 * The gesture is handled by the list itself, not by the row, and the row that is being
 * dragged is drawn in a layer on top of the list. That is what makes a long drag survive:
 * when the list scrolls, the row's own slot can leave the visible area and be discarded —
 * and with the gesture inside the row, discarding it cancelled the drag, which is exactly
 * how a drag used to die at the top of the list.
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

    // A local copy so a drag can move a row before it is saved. While a finger is down the
    // stored list is ignored; otherwise a new emission would yank the row.
    val rows = remember { mutableStateListOf<Competition>() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    // Where the finger is, in pixels from the top of the list. The dragged row is drawn from
    // this number alone, so it can never drift away from the finger.
    var pointerY by remember { mutableFloatStateOf(0f) }
    // Which part of the row was grabbed, so the row does not jump when the drag starts.
    var grabOffsetY by remember { mutableFloatStateOf(0f) }
    var draggedHeightPx by remember { mutableFloatStateOf(0f) }
    // Pixels per second; sign is the direction. Zero means the finger is not near an edge.
    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }
    val colors = FootballAiThemeExtra.colors

    LaunchedEffect(state.competitions) {
        if (draggingId == null) {
            rows.clear()
            rows.addAll(state.competitions)
        }
    }

    fun viewportHeightPx(): Float = listState.layoutInfo.viewportSize.height.toFloat()

    /** The row drawn at this height, ignoring the footer item. */
    fun rowAt(y: Float): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.index in rows.indices && y >= item.offset && y < item.offset + item.size
        }

    /**
     * Puts the dragged row into whichever slot the finger is over.
     *
     * Pure geometry against the layout on screen: no accumulated offset, no guessed row
     * height, so it cannot drift. The dragged row is left out of the search, so the slot it
     * already occupies cannot pull it back and forth.
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
            // Below the last row there is only the reset button; a drag down there still
            // means "to the end of the list".
            ?: candidates.last().takeIf { pointerY >= it.offset + it.size }
            ?: return
        if (target.index == from) return
        val moved = rows.removeAt(from)
        rows.add(target.index, moved)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /**
     * Scroll speed for where the finger is: zero in the middle of the list, growing towards
     * [MAX_SCROLL_PX_PER_SECOND] inside the edge band.
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

    // One frame at a time: decide the slot, then scroll if the finger is in an edge band.
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
            maybeReorder()
            val viewport = viewportHeightPx()
            updateAutoScroll(viewport)
            val speed = autoScrollSpeed
            if (speed != 0f) {
                listState.scrollBy(speed * dt)
            }
        }
    }

    fun startDragAt(y: Float) {
        val item = rowAt(y) ?: return
        draggingId = rows[item.index].id
        pointerY = y
        grabOffsetY = (y - item.offset).coerceIn(0f, (item.size - 1).toFloat())
        draggedHeightPx = item.size.toFloat()
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun moveDragBy(deltaY: Float) {
        if (draggingId == null) return
        val viewport = viewportHeightPx()
        if (viewport <= 0f) return
        // Clamp the row itself, not the finger: it may reach the first and the last slot,
        // and it may never be drawn outside the list — that is what used to look like the
        // row flying off the top of the screen.
        val top = (pointerY - grabOffsetY + deltaY)
            .coerceIn(0f, (viewport - draggedHeightPx).coerceAtLeast(0f))
        pointerY = top + grabOffsetY
        maybeReorder()
        updateAutoScroll(viewport)
    }

    fun drop() {
        if (draggingId == null) return
        draggingId = null
        autoScrollSpeed = 0f
        viewModel.applyOrder(rows.map { it.id })
    }

    val draggedRow = rows.firstOrNull { it.id == draggingId }

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
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    // The scroll and the drag cannot both own the finger; while a row is
                    // being dragged the list is scrolled by the frame loop instead.
                    userScrollEnabled = draggingId == null,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { start -> startDragAt(start.y) },
                                onDrag = { change, dragAmount ->
                                    if (draggingId != null) {
                                        change.consume()
                                        moveDragBy(dragAmount.y)
                                    }
                                },
                                onDragEnd = { drop() },
                                onDragCancel = { drop() },
                            )
                        },
                ) {
                    items(rows, key = { it.id }) { competition ->
                        val isDragged = competition.id == draggingId
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItemPlacement(tween(130))
                                // The row lives on in the floating layer while it is being
                                // dragged; its slot keeps the space, but stays empty.
                                .alpha(if (isDragged) 0f else 1f),
                        ) {
                            OrderRow(
                                competition = competition,
                                languageTag = languageTag,
                                containerColor = Color.Transparent,
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            )
                        }
                    }
                    item {
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = viewModel::reset, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(stringResource(R.string.action_reset_order))
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
                if (draggedRow != null) {
                    OrderRow(
                        competition = draggedRow,
                        languageTag = languageTag,
                        containerColor = colors.cardStrong,
                        modifier = Modifier
                            .zIndex(2f)
                            .offset { IntOffset(0, (pointerY - grabOffsetY).roundToInt()) }
                            .shadow(10.dp),
                    )
                }
            }
        }
    }
}

/** One tournament in the order list: its names, where it comes from, and the grip. */
@Composable
private fun OrderRow(
    competition: Competition,
    languageTag: String,
    containerColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor)
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
                .size(26.dp),
        )
    }
}
