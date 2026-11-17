package com.moneytracker.app.ui.screens.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moneytracker.app.R
import com.moneytracker.app.ui.theme.Spacing
import com.moneytracker.app.utils.CurrencyFormatter
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * A month grid showing what was spent each day, either for the current Analytics
 * filters or for all spending of the selected type view. Opens on the latest
 * month that has spending.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingCalendar(
    filtered: Map<LocalDate, BigDecimal>,
    all: Map<LocalDate, BigDecimal>,
    currency: String,
    modifier: Modifier = Modifier
) {
    var showAll by remember { mutableStateOf(false) }
    val data = if (showAll) all else filtered
    val latest = (all.keys + filtered.keys).maxOrNull() ?: return
    val earliest = (all.keys + filtered.keys).minOrNull() ?: return
    var month by remember(latest) { mutableStateOf(YearMonth.from(latest)) }
    val locale = Locale.getDefault()
    val first = WeekFields.of(locale).firstDayOfWeek

    val monthTotal = data.filterKeys { YearMonth.from(it) == month }.values.fold(BigDecimal.ZERO, BigDecimal::add)
    val monthMax = data.filterKeys { YearMonth.from(it) == month }.values.maxOrNull() ?: BigDecimal.ZERO

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(selected = !showAll, onClick = { showAll = false }, label = { Text(stringResource(R.string.analytics_calendar_filtered)) })
            FilterChip(selected = showAll, onClick = { showAll = true }, label = { Text(stringResource(R.string.analytics_calendar_all)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(
                onClick = { month = month.minusMonths(1) },
                enabled = month > YearMonth.from(earliest)
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.analytics_calendar_prev), tint = MaterialTheme.colorScheme.onSurface) }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    month.format(DateTimeFormatter.ofPattern("MMMM yyyy", locale)),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.analytics_calendar_total, CurrencyFormatter.formatCurrency(monthTotal, currency)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = { month = month.plusMonths(1) },
                enabled = month < YearMonth.from(latest)
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.analytics_calendar_next), tint = MaterialTheme.colorScheme.onSurface) }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            (0L until 7L).forEach { offset ->
                Text(
                    first.plus(offset).getDisplayName(TextStyle.NARROW, locale),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        val lead = (month.atDay(1).dayOfWeek.value - first.value + 7) % 7
        val cells = lead + month.lengthOfMonth()
        val rows = (cells + 6) / 7
        for (r in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (c in 0 until 7) {
                    val day = r * 7 + c - lead + 1
                    if (day < 1 || day > month.lengthOfMonth()) {
                        Box(modifier = Modifier.weight(1f).aspectRatio(0.8f))
                    } else {
                        val spent = data[month.atDay(day)] ?: BigDecimal.ZERO
                        val intensity = if (monthMax > BigDecimal.ZERO)
                            spent.divide(monthMax, 3, java.math.RoundingMode.HALF_UP).toFloat() else 0f
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(0.8f)
                                .background(
                                    if (spent > BigDecimal.ZERO)
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f + 0.5f * intensity)
                                    else MaterialTheme.colorScheme.surfaceContainerLow,
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(day.toString(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                            if (spent > BigDecimal.ZERO) {
                                Text(
                                    CurrencyFormatter.formatAbbreviated(spent.toDouble(), currency),
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
