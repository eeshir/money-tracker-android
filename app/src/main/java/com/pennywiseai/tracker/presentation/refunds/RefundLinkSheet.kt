package com.pennywiseai.tracker.presentation.refunds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.data.refunds.RefundLinks
import com.pennywiseai.tracker.utils.CurrencyFormatter
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy")

/**
 * Chooses which expenses a refund pays back and how much goes on each. What is not placed anywhere is the floater:
 * it counts for nothing until it is linked. Saving replaces the refund's links.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefundLinkSheet(
    refundId: Long,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    onReverted: (com.pennywiseai.tracker.data.repository.RefundLinkRepository.Previous) -> Unit,
    viewModel: RefundLinkViewModel = hiltViewModel()
) {
    LaunchedEffect(refundId) { viewModel.load(refundId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.consumeSaved()
            onSaved()
        }
    }
    val refund = state.refund
    // Closing without saving links: undo making it a refund, if opening the sheet did that.
    fun close() = viewModel.dismiss { before -> before?.let(onReverted); onDismiss() }
    ModalBottomSheet(
        onDismissRequest = { close() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.refund_link_title), style = MaterialTheme.typography.titleLarge)
            if (refund == null) {
                Text(
                    stringResource(if (state.unavailable) R.string.refund_link_not_saved else R.string.refund_link_loading),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (state.unavailable) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                        Button(onClick = { close() }) { Text(stringResource(R.string.refund_link_ok)) }
                    }
                }
                return@Column
            }
            val currency = refund.currency
            Text(
                stringResource(R.string.refund_link_summary, CurrencyFormatter.formatCurrency(refund.amount, currency)),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                stringResource(R.string.refund_link_floater_line, CurrencyFormatter.formatCurrency(state.linked, currency), CurrencyFormatter.formatCurrency(state.floater.max(java.math.BigDecimal.ZERO), currency)),
                style = MaterialTheme.typography.titleMedium,
                color = if (state.overRefund) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            if (state.overRefund) {
                Text(stringResource(R.string.refund_link_over_refund), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else if (state.floater.signum() > 0) {
                Text(stringResource(R.string.refund_link_floater_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.notice?.let {
                Text(stringResource(R.string.refund_link_cut_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(
                value = state.query, onValueChange = viewModel::setQuery, singleLine = true,
                label = { Text(stringResource(R.string.refund_link_search)) }, modifier = Modifier.fillMaxWidth()
            )
            val shown = state.visibleTargets
            if (shown.isEmpty()) {
                Text(stringResource(R.string.refund_link_none), style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(shown, key = { it.key }) { target ->
                    val e = target.expense
                    val typed = state.inputs[target.key].orEmpty()
                    val over = (typed.trim().toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) > target.room
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(
                            e.merchantName + (target.category?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "${e.dateTime.format(DAY)} · ${CurrencyFormatter.formatCurrency(target.cost, e.currency)}" +
                                if (target.takenByOthers.signum() > 0) " · " + stringResource(R.string.refund_link_already, CurrencyFormatter.formatCurrency(target.takenByOthers, e.currency)) else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = typed, onValueChange = { viewModel.setAmount(target.key, it) }, singleLine = true,
                                isError = over,
                                supportingText = if (over) ({ Text(stringResource(R.string.refund_link_room, CurrencyFormatter.formatCurrency(target.room, e.currency))) }) else null,
                                label = { Text(stringResource(R.string.refund_link_amount)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.width(160.dp)
                            )
                            TextButton(onClick = { viewModel.fill(target.key) }, enabled = target.room.signum() > 0) { Text(stringResource(R.string.refund_link_fill)) }
                            if (typed.isNotBlank()) TextButton(onClick = { viewModel.clear(target.key) }) { Text(stringResource(R.string.refund_link_clear)) }
                        }
                    }
                    HorizontalDivider()
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { close() }) { Text(stringResource(R.string.card_rewards_cancel)) }
                Button(onClick = viewModel::save, enabled = state.canSave) { Text(stringResource(R.string.refund_link_save)) }
            }
        }
    }
}

/** A plain-words line for a [RefundLinks.Problem], for places that list them. */
@Composable
fun refundProblemText(p: RefundLinks.Problem): String = when (p) {
    is RefundLinks.Problem.CutToRoom -> stringResource(R.string.refund_link_problem_cut)
    is RefundLinks.Problem.OverRefund -> stringResource(R.string.refund_link_over_refund)
    else -> stringResource(R.string.refund_link_problem_other)
}
