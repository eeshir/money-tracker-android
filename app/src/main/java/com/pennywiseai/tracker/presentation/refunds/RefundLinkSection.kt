package com.pennywiseai.tracker.presentation.refunds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.utils.CurrencyFormatter
import java.math.BigDecimal

/** The linked / floater figures of a refund, as one line of text. */
@Composable
private fun refundStatus(refundId: Long, amount: BigDecimal, currency: String, viewModel: RefundLinkViewModel): String {
    val linked by remember(refundId) { viewModel.linkedTotal(refundId) }.collectAsStateWithLifecycle(initialValue = BigDecimal.ZERO)
    return stringResource(
        R.string.refund_section_status,
        CurrencyFormatter.formatCurrency(linked, currency),
        CurrencyFormatter.formatCurrency((amount - linked).max(BigDecimal.ZERO), currency)
    )
}

/** Edit-mode block on a refund: what is linked, and the button that opens the linking sheet. */
@Composable
fun RefundLinksSection(
    refundId: Long,
    amount: BigDecimal,
    currency: String,
    onLinksSaved: () -> Unit,
    /** Linking was abandoned and the transaction went back to what it was: reset the form to this. */
    onReverted: (impact: com.pennywiseai.tracker.data.database.entity.BudgetImpactType?, category: String?) -> Unit,
    viewModel: RefundLinkViewModel = hiltViewModel()
) {
    var open by remember { mutableStateOf(false) }
    val status = refundStatus(refundId, amount, currency, viewModel)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.refund_section_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.refund_section_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(status, style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = null)
            Text(stringResource(R.string.refund_section_button), modifier = Modifier.padding(start = 8.dp))
        }
    }
    if (open) {
        RefundLinkSheet(
            refundId = refundId,
            onDismiss = { open = false },
            onSaved = { open = false; viewModel.reset(); onLinksSaved() },
            onReverted = { before -> onReverted(before.impact, before.category) },
            viewModel = viewModel
        )
    }
}

/** Read-only row on a refund's detail: linked amount and floater. */
@Composable
fun RefundLinkedRow(refundId: Long, amount: BigDecimal, currency: String, viewModel: RefundLinkViewModel = hiltViewModel()) {
    val status = refundStatus(refundId, amount, currency, viewModel)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(stringResource(R.string.refund_section_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
