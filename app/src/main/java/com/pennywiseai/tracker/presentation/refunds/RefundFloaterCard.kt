package com.pennywiseai.tracker.presentation.refunds

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.utils.CurrencyFormatter
import com.pennywiseai.tracker.utils.sumByCurrency
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("d MMM")

/** At most this many refunds are listed on the card; the rest are counted. */
private const val SHOWN = 3

/**
 * Home card for refund money that is not linked to any expense yet (the floater). It is only drawn when there is
 * some: the caller passes an empty list otherwise. Totals are per currency, never added across currencies.
 * Tapping a refund opens it, where it can be linked.
 */
@Composable
fun RefundFloaterCard(
    floaters: List<RefundFloater>,
    onOpenRefund: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (floaters.isEmpty()) return
    val total = floaters.sumByCurrency({ it.refund.currency }, { it.amount })
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = null, modifier = Modifier.size(32.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.refund_floater_title), style = MaterialTheme.typography.titleMedium)
                    Text(CurrencyFormatter.formatByCurrency(total), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        pluralStringResource(R.plurals.refund_floater_count, floaters.size, floaters.size),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            floaters.take(SHOWN).forEach { f ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenRefund(f.refund.id) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(f.refund.merchantName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            f.refund.dateTime.format(DAY),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }
                    Text(CurrencyFormatter.formatCurrency(f.amount, f.refund.currency), style = MaterialTheme.typography.titleSmall)
                }
            }
            if (floaters.size > SHOWN) {
                Text(
                    stringResource(R.string.refund_floater_more, floaters.size - SHOWN),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
