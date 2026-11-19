package com.footballai.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.footballai.app.R
import com.footballai.app.core.Formatters
import com.footballai.app.core.MatchCalendarDates
import com.footballai.app.ui.LocalLanguageTag
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun MatchCalendarDialog(
    selectedDayOffset: Int,
    onSelectDate: (Long) -> Unit,
    onToday: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalContext.current.resources
    val direction = LocalLayoutDirection.current
    val language = LocalLanguageTag.current
    val persian = Formatters.isPersian(language)
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val min = today.minusDays(MatchCalendarDates.MAX_DAY_OFFSET.toLong())
    val max = today.plusDays(MatchCalendarDates.MAX_DAY_OFFSET.toLong())
    var selected by remember { mutableStateOf(today.plusDays(selectedDayOffset.toLong())) }
    var month by remember { mutableStateOf(MatchCalendarDates.month(selected, persian, zone)) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.92f).dp
    val firstDay = if (persian) DayOfWeek.SATURDAY else DayOfWeek.MONDAY
    val weekdays = if (persian) listOf("ش", "ی", "د", "س", "چ", "پ", "ج")
        else listOf("M", "T", "W", "T", "F", "S", "S")
    val leading = MatchCalendarDates.leadingDays(month.first, firstDay)
    val rows = (leading + month.length + 6) / 7
    val previous = MatchCalendarDates.shift(month, -1, persian, zone)
    val next = MatchCalendarDates.shift(month, 1, persian, zone)
    val previousYear = MatchCalendarDates.shift(month, -12, persian, zone)
    val nextYear = MatchCalendarDates.shift(month, 12, persian, zone)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            Surface(modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = maxHeight),
                shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(resources.getString(R.string.calendar_title), modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = resources.getString(R.string.action_close))
                        }
                    }
                    Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)) {
                        Text(resources.getString(R.string.calendar_selected), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Formatters.dateOnly(selected.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), language),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { month = previousYear }, enabled = previousYear.intersects(min, max), modifier = Modifier.size(36.dp)) {
                                Text(if (direction == LayoutDirection.Rtl) "»" else "«", style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.semantics { contentDescription = resources.getString(R.string.calendar_previous_year) })
                            }
                            IconButton(onClick = { month = previous }, enabled = previous.intersects(min, max), modifier = Modifier.size(40.dp)) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = resources.getString(R.string.calendar_previous_month))
                            }
                            Text(MatchCalendarDates.monthLabel(month, persian, language), modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = { month = next }, enabled = next.intersects(min, max), modifier = Modifier.size(40.dp)) {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = resources.getString(R.string.calendar_next_month))
                            }
                            IconButton(onClick = { month = nextYear }, enabled = nextYear.intersects(min, max), modifier = Modifier.size(36.dp)) {
                                Text(if (direction == LayoutDirection.Rtl) "«" else "»", style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.semantics { contentDescription = resources.getString(R.string.calendar_next_year) })
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth()) {
                            weekdays.forEach { label ->
                                Text(label, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        repeat(rows) { row ->
                            Row(Modifier.fillMaxWidth()) {
                                repeat(7) { column ->
                                    val day = row * 7 + column - leading + 1
                                    Box(modifier = Modifier.weight(1f).height(44.dp), contentAlignment = Alignment.Center) {
                                        if (day in 1..month.length) {
                                            val date = month.first.plusDays((day - 1).toLong())
                                            val enabled = date >= min && date <= max
                                            val isSelected = date == selected
                                            val dateLabel = Formatters.dateOnly(date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), language)
                                            Surface(shape = CircleShape,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                                border = if (date == today && !isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                                                modifier = Modifier.size(40.dp).selectable(selected = isSelected, enabled = enabled,
                                                    role = Role.RadioButton, onClick = { selected = date })
                                                    .semantics { contentDescription = dateLabel }) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(Formatters.integer(day, language), style = MaterialTheme.typography.bodyMedium,
                                                        color = when {
                                                            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                                            isSelected -> MaterialTheme.colorScheme.onPrimary
                                                            else -> MaterialTheme.colorScheme.onSurface
                                                        })
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Text(resources.getString(R.string.calendar_data_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onToday(); onDismiss() }) { Text(resources.getString(R.string.filter_today)) }
                        Row {
                            TextButton(onClick = onDismiss) { Text(resources.getString(R.string.action_cancel)) }
                            Spacer(Modifier.width(4.dp))
                            TextButton(onClick = {
                                onSelectDate(selected.atStartOfDay(zone).toInstant().toEpochMilli())
                                onDismiss()
                            }) { Text(resources.getString(R.string.action_done)) }
                        }
                    }
                }
            }
        }
    }
}
