package com.pennywiseai.tracker.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.domain.spending.CardDueItem
import com.pennywiseai.tracker.utils.CurrencyFormatter
import java.time.format.DateTimeFormatter

private val DUE_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/**
 * Home card that calls out credit-card bills still to pay, soonest first,
 * coloured by urgency (overdue or due within 3 days stands out).
 */
@Composable
fun CardDueSection(
    items: List<CardDueItem>,
    onMarkPaid: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item -> CardDueRow(item, onMarkPaid) }
    }
}

@Composable
private fun CardDueRow(item: CardDueItem, onMarkPaid: (String) -> Unit) {
    val urgent = item.daysLeft <= 3
    val container = if (urgent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (urgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val whenText = when {
        item.daysLeft < 0 -> stringResource(R.string.card_due_overdue_days, (-item.daysLeft).toInt())
        item.daysLeft == 0L -> stringResource(R.string.card_due_today)
        item.daysLeft == 1L -> stringResource(R.string.card_due_tomorrow)
        else -> stringResource(R.string.card_due_in_days, item.daysLeft.toInt())
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.CreditCard, contentDescription = null, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.cardLabel, style = MaterialTheme.typography.titleMedium)
                    Text("$whenText · ${item.dueDate.format(DUE_DATE_FORMAT)}", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    CurrencyFormatter.formatCurrency(item.remaining, item.currency),
                    style = MaterialTheme.typography.titleLarge
                )
            }
            val detail = when {
                item.minimumPaid -> stringResource(R.string.card_due_minimum_paid)
                item.minimumDue != null -> stringResource(
                    R.string.card_due_minimum, CurrencyFormatter.formatCurrency(item.minimumDue, item.currency)
                )
                else -> null
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(detail.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { onMarkPaid(item.key) }) { Text(stringResource(R.string.card_due_mark_paid)) }
            }
        }
    }
}
