package com.moneytracker.app.presentation.refunds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moneytracker.app.R
import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.utils.CurrencyFormatter
import java.math.BigDecimal
import java.time.format.DateTimeFormatter

private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy")

/**
 * Expense-page block: shows what refunds have paid back, and a button to choose which refunds pay this expense back.
 * Drawn only when there is something to do: a refund with money left, or refunds already linked here.
 */
@Composable
fun ExpenseRefundSection(
    expense: TransactionEntity,
    onLinksSaved: () -> Unit,
    viewModel: ExpenseRefundLinkViewModel = hiltViewModel()
) {
    val hasRefunds by remember(expense.currency) { viewModel.hasRefundsToLink(expense.currency) }
        .collectAsStateWithLifecycle(initialValue = false)
    val alreadyLinked = (expense.refundedTotal ?: BigDecimal.ZERO).signum() > 0
    if (!hasRefunds && !alreadyLinked) return
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        FilledTonalButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = null)
            Text(
                stringResource(if (alreadyLinked) R.string.expense_link_button_edit else R.string.expense_link_button),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
    if (open) {
        ExpenseRefundLinkSheet(
            expenseId = expense.id,
            onDismiss = { open = false },
            onSaved = { open = false; onLinksSaved() },
            viewModel = viewModel
        )
    }
}

/** Chooses which refunds pay an expense back, and how much each puts on it (or on one part of it). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExpenseRefundLinkSheet(
    expenseId: Long,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ExpenseRefundLinkViewModel = hiltViewModel()
) {
    LaunchedEffect(expenseId) { viewModel.load(expenseId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.consumeSaved()
            viewModel.reset()
            onSaved()
        }
    }
    fun close() { viewModel.reset(); onDismiss() }
    val expense = state.expense
    ModalBottomSheet(onDismissRequest = { close() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.expense_link_title), style = MaterialTheme.typography.titleLarge)
            if (expense == null) {
                Text(
                    stringResource(if (state.unavailable) R.string.expense_link_unavailable else R.string.refund_link_loading),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                    Button(onClick = { close() }) { Text(stringResource(R.string.refund_link_ok)) }
                }
                return@Column
            }
            val currency = expense.currency
            if (state.parts.size > 1) {
                Text(stringResource(R.string.expense_link_which_part), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.parts.forEach { p ->
                        FilterChip(
                            selected = p == state.part,
                            onClick = { viewModel.selectPart(p) },
                            label = { Text((p ?: "") + " · " + CurrencyFormatter.formatCurrency(state.costOfPart[p] ?: BigDecimal.ZERO, currency)) }
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.expense_link_summary, CurrencyFormatter.formatCurrency(state.cost, currency)),
                style = MaterialTheme.typography.bodyMedium
            )
            val over = state.overCost || state.overRefund
            Text(
                stringResource(
                    R.string.expense_link_status,
                    CurrencyFormatter.formatCurrency(state.typedTotal, currency),
                    CurrencyFormatter.formatCurrency((state.cost - state.typedTotal).max(BigDecimal.ZERO), currency)
                ),
                style = MaterialTheme.typography.titleMedium,
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            if (state.overCost) {
                Text(stringResource(R.string.expense_link_over_cost), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (state.notice) {
                Text(stringResource(R.string.refund_link_cut_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            val options = state.options
            if (options.isEmpty()) {
                Text(stringResource(R.string.expense_link_none), style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(options, key = { it.refund.id }) { option ->
                    val r = option.refund
                    val typed = state.inputs[r.id].orEmpty()
                    val tooMuch = (typed.trim().toBigDecimalOrNull() ?: BigDecimal.ZERO) > option.available
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(r.merchantName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${r.dateTime.format(DAY)} · " + CurrencyFormatter.formatCurrency(r.amount, r.currency) + " · " +
                                stringResource(R.string.expense_link_available, CurrencyFormatter.formatCurrency(option.available, r.currency)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = typed, onValueChange = { viewModel.setAmount(r.id, it) }, singleLine = true, isError = tooMuch,
                                supportingText = if (tooMuch) ({ Text(stringResource(R.string.refund_link_room, CurrencyFormatter.formatCurrency(option.available, r.currency))) }) else null,
                                label = { Text(stringResource(R.string.refund_link_amount)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.width(160.dp)
                            )
                            TextButton(onClick = { viewModel.fill(r.id) }) { Text(stringResource(R.string.refund_link_fill)) }
                            if (typed.isNotBlank()) TextButton(onClick = { viewModel.clear(r.id) }) { Text(stringResource(R.string.refund_link_clear)) }
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
