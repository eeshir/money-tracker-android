package com.pennywiseai.tracker.presentation.rewards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.data.rewards.CardRewardPresets
import com.pennywiseai.tracker.data.rewards.TransactionReward
import com.pennywiseai.tracker.ui.components.PennyWiseScaffold
import com.pennywiseai.tracker.ui.theme.Dimensions
import com.pennywiseai.tracker.ui.theme.Spacing
import com.pennywiseai.tracker.utils.CurrencyFormatter
import com.pennywiseai.tracker.utils.sumByCurrency
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
fun CardRewardsScreen(
    onNavigateBack: () -> Unit,
    onTransactionClick: (Long) -> Unit = {},
    viewModel: CardRewardsViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val duplicates by viewModel.duplicates.collectAsStateWithLifecycle()
    val advanced by viewModel.advanced.collectAsStateWithLifecycle()
    var selectedCardId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    var sortName by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(RewardSort.NEWEST.name) }
    var earningOnly by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CardRewardsItem?>(null) }
    // Never add across currencies: a per-currency map, formatted together.
    val grandTotal = items.flatMap { it.rewards }.sumByCurrency({ it.transaction.currency }, { it.reward })

    PennyWiseScaffold(
        title = stringResource(R.string.card_rewards_title),
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.import_statement_back))
            }
        },
        actions = {
            // Flip between the simple view and the detailed one.
            TextButton(onClick = { viewModel.setAdvanced(!advanced) }) {
                Text(stringResource(if (advanced) R.string.rewards_mode_simple else R.string.rewards_mode_advanced))
            }
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = Dimensions.Padding.content, end = Dimensions.Padding.content,
                top = padding.calculateTopPadding() + Dimensions.Padding.content,
                bottom = padding.calculateBottomPadding() + Dimensions.Padding.content
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (duplicates.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth(), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.card_rewards_duplicates_title, duplicates.size), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.card_rewards_duplicates_body), style = MaterialTheme.typography.bodySmall)
                            var reviewing by remember { mutableStateOf(false) }
                            if (reviewing) {
                                // Exactly what Merge would remove, and the copy it would keep.
                                duplicates.forEach { d ->
                                    Text(
                                        "${d.drop.bankName} ${d.drop.accountNumber} · ${d.drop.dateTime.format(DATE)} · ${d.drop.merchantName} · " +
                                            "${CurrencyFormatter.formatCurrency(d.drop.amount, d.drop.currency)} → " +
                                            stringResource(R.string.card_rewards_duplicates_keeps, d.keep.dateTime.format(DATE)),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            TextButton(onClick = { reviewing = !reviewing }) { Text(stringResource(R.string.card_rewards_duplicates_review)) }
                            TextButton(onClick = viewModel::mergeDuplicates) { Text(stringResource(R.string.card_rewards_duplicates_merge)) }
                        }
                    }
                }
            }
            if (!advanced) {
                simpleRewards(
                    items = items, period = period, onPeriod = viewModel::setPeriod,
                    selectedCardId = selectedCardId, onSelectCard = { selectedCardId = it },
                    sort = RewardSort.valueOf(sortName), onSort = { sortName = it.name },
                    earningOnly = earningOnly, onEarningOnly = { earningOnly = it },
                    onEdit = { editing = it }, onTransactionClick = onTransactionClick,
                    onShowAdvanced = { viewModel.setAdvanced(true) }
                )
                return@LazyColumn
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    RewardPeriod.values().forEach { p ->
                        FilterChip(
                            selected = p == period,
                            onClick = { viewModel.setPeriod(p) },
                            label = { Text(stringResource(when (p) {
                                RewardPeriod.THIS_MONTH -> R.string.card_rewards_period_month
                                RewardPeriod.LAST_3_MONTHS -> R.string.card_rewards_period_3m
                                RewardPeriod.ALL -> R.string.card_rewards_period_all
                            })) }
                        )
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding16()) {
                        Text(stringResource(R.string.card_rewards_total_label), style = MaterialTheme.typography.labelLarge)
                        Text(
                            CurrencyFormatter.formatByCurrency(grandTotal),
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(
                            stringResource(R.string.card_rewards_estimate_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (items.isEmpty()) {
                item { Text(stringResource(R.string.card_rewards_no_cards), style = MaterialTheme.typography.bodyMedium) }
            }
            items(items, key = { it.card.id }) { item ->
                CardSection(item, onEdit = { editing = item })
            }
        }
    }

    editing?.let { item ->
        // Variants already saved on the bank's other cards, so the suggestion offered here is the card not yet set up.
        val takenNames = items.filter { it.card.id != item.card.id && it.card.bankName.equals(item.card.bankName, ignoreCase = true) }
            .mapNotNull { it.program?.name?.trim()?.lowercase() }.toSet()
        EditProgramDialog(item, takenNames, onDismiss = { editing = null }) { name, rate, cap, pointValue, rules, excluded ->
            viewModel.save(item.card, name, rate, cap, pointValue, rules, excluded)
            editing = null
        }
    }
}

private fun Modifier.padding16() = this.then(Modifier.padding(16.dp))

@Composable
private fun CardSection(item: CardRewardsItem, onEdit: () -> Unit) {
    var showTransactions by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding16()) {
            val title = item.program?.name?.ifBlank { null } ?: item.card.nickname ?: item.card.bankName
            Text("$title ····${item.card.cardLast4}", style = MaterialTheme.typography.titleMedium)
            if (item.program == null) {
                Text(
                    stringResource(R.string.card_rewards_not_set_up),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    CurrencyFormatter.formatByCurrency(item.totals),
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    stringResource(R.string.card_rewards_card_summary, item.rewards.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onEdit) { Text(stringResource(R.string.card_rewards_edit_rates)) }
            if (item.program != null) StatementChecks(item)
            if (item.rewards.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterChip(
                        selected = !showTransactions, onClick = { showTransactions = false },
                        label = { Text(stringResource(R.string.card_rewards_tab_merchants)) }
                    )
                    FilterChip(
                        selected = showTransactions, onClick = { showTransactions = true },
                        label = { Text(stringResource(R.string.card_rewards_tab_transactions)) }
                    )
                }
                if (showTransactions) {
                    item.rewards.sortedByDescending { it.transaction.dateTime }.take(100).forEach { RewardRow(it) }
                } else {
                    item.rewards.byMerchant().take(8).forEach { MerchantRow(it) }
                }
            }
        }
    }
}

@Composable
private fun RewardRow(r: TransactionReward) {
    ListItem(
        headlineContent = { Text(r.transaction.merchantName) },
        supportingContent = {
            val basis = when {
                r.refund -> stringResource(R.string.card_rewards_refund) + " · " + r.ratePercent.stripTrailingZeros().toPlainString() + "%"
                r.excluded -> stringResource(R.string.card_rewards_excluded)
                else -> "${r.ruleLabel ?: stringResource(R.string.card_rewards_base_rate)} · ${r.ratePercent.stripTrailingZeros().toPlainString()}%" +
                    if (r.capped) " · " + stringResource(R.string.card_rewards_capped) else ""
            }
            Text("${r.transaction.dateTime.format(DATE)} · ${CurrencyFormatter.formatCurrency(r.transaction.amount, r.transaction.currency)} · $basis")
        },
        trailingContent = { Text(CurrencyFormatter.formatCurrency(r.reward, r.transaction.currency)) }
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EditProgramDialog(
    item: CardRewardsItem,
    takenNames: Set<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, rate: String, cap: String, pointValue: String, rules: String, excluded: String) -> Unit
) {
    // A card with nothing saved starts from a suggestion for its bank, which the person must check. When the bank has
    // several cards (HDFC) the app cannot tell which this is, so it only pre-fills when the others are already set up
    // and exactly one variant is left; otherwise the person picks with the buttons.
    val all = CardRewardPresets.suggestionsFor(item.card.bankName)
    val p = item.program ?: if (all.size <= 1) all.firstOrNull() else all.filter { it.name.lowercase() !in takenNames }.singleOrNull()
    var name by remember { mutableStateOf(p?.name.orEmpty()) }
    var rate by remember { mutableStateOf(p?.baseRatePercent ?: "") }
    var cap by remember { mutableStateOf(p?.baseMonthlyCap.orEmpty()) }
    var pointValue by remember { mutableStateOf(p?.pointValue.orEmpty()) }
    var rules by remember { mutableStateOf(CardRewardsViewModel.rulesToText(p?.rules.orEmpty())) }
    var excluded by remember { mutableStateOf(p?.excludedKeywords?.joinToString(", ").orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.card_rewards_edit_rates)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                // Which card is this? Picking one fills the form below with that card's suggested rates.
                if (all.isNotEmpty()) {
                    Text(
                        stringResource(if (all.size > 1) R.string.card_rewards_pick_card else R.string.card_rewards_use_suggested),
                        style = MaterialTheme.typography.labelLarge
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        all.forEach { s ->
                            FilterChip(
                                selected = name.trim().equals(s.name, ignoreCase = true),
                                onClick = {
                                    name = s.name; rate = s.baseRatePercent; cap = s.baseMonthlyCap.orEmpty(); pointValue = s.pointValue.orEmpty()
                                    rules = CardRewardsViewModel.rulesToText(s.rules); excluded = s.excludedKeywords.joinToString(", ")
                                },
                                label = { Text(s.name) }
                            )
                        }
                    }
                    androidx.compose.material3.HorizontalDivider()
                }
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.card_rewards_field_name)) }, singleLine = true)
                OutlinedTextField(rate, { rate = it }, label = { Text(stringResource(R.string.card_rewards_field_base_rate)) }, singleLine = true)
                OutlinedTextField(cap, { cap = it }, label = { Text(stringResource(R.string.card_rewards_field_base_cap)) }, singleLine = true)
                OutlinedTextField(pointValue, { pointValue = it }, label = { Text(stringResource(R.string.card_rewards_field_point_value)) }, singleLine = true)
                OutlinedTextField(
                    rules, { rules = it }, label = { Text(stringResource(R.string.card_rewards_field_rules)) },
                    supportingText = { Text(stringResource(R.string.card_rewards_field_rules_hint)) }, minLines = 3
                )
                OutlinedTextField(excluded, { excluded = it }, label = { Text(stringResource(R.string.card_rewards_field_excluded)) })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, rate, cap, pointValue, rules, excluded) }) { Text(stringResource(R.string.card_rewards_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.card_rewards_cancel)) } }
    )
}

@Composable
private fun MerchantRow(line: MerchantRewardLine) {
    val currency = line.currency
    ListItem(
        headlineContent = { Text(line.merchant) },
        supportingContent = {
            Text(
                "${CurrencyFormatter.formatCurrency(line.spend, currency)} · " +
                    stringResource(R.string.card_rewards_purchases, line.count) +
                    if (line.baseOnly) " · " + stringResource(R.string.card_rewards_base_only) else ""
            )
        },
        trailingContent = { Text(CurrencyFormatter.formatCurrency(line.reward, currency)) }
    )
}

/** Each saved statement's reward total next to the app's estimate for that cycle, flagging gaps. */
@Composable
private fun StatementChecks(item: CardRewardsItem) {
    if (item.checks.isEmpty()) return
    var showLines by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<com.pennywiseai.tracker.data.rewards.StatementCheck?>(null) }
    selected?.let { CycleDetailDialog(it, item.currency) { selected = null } }
    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.card_rewards_statement_check), style = MaterialTheme.typography.titleSmall)
        item.checks.take(12).forEach { c ->
            val estimate = CurrencyFormatter.formatCurrency(c.estimate, item.currency)
            val line = when {
                c.reported != null ->
                    stringResource(R.string.card_rewards_check_row, CurrencyFormatter.formatCurrency(c.reported, item.currency), estimate)
                c.reportedPoints != null ->
                    stringResource(R.string.card_rewards_check_points, c.reportedPoints.toPlainString(), estimate)
                else -> stringResource(R.string.card_rewards_check_none, estimate)
            }
            ListItem(
                modifier = Modifier.clickable { selected = c },
                headlineContent = { Text(stringResource(R.string.card_rewards_cycle_ending, c.to.format(DATE))) },
                supportingContent = { Text(line) },
                trailingContent = {
                    c.difference?.let {
                        Text(
                            (if (c.mismatch) "⚠ " else "✓ ") + CurrencyFormatter.formatCurrency(it, item.currency),
                            color = if (c.mismatch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
        if (item.checks.any { it.lines.isNotEmpty() }) {
            TextButton(onClick = { showLines = !showLines }) { Text(stringResource(R.string.card_rewards_check_lines)) }
            if (showLines) {
                item.checks.first { it.lines.isNotEmpty() }.lines.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** What went into one statement check: the cycle, the statement's figure, and the purchases counted. */
@Composable
private fun CycleDetailDialog(c: com.pennywiseai.tracker.data.rewards.StatementCheck, currency: String, onDismiss: () -> Unit) {
    var all by remember { mutableStateOf(false) }
    val fromSms = c.counted.count { it.transaction.smsSender?.endsWith("CC PDF") != true }
    val fromStatement = c.counted.size - fromSms
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${c.from.format(DATE)} – ${c.to.format(DATE)}") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                item {
                    Text(
                        stringResource(
                            if (c.forPreviousCycle) R.string.card_rewards_cycle_prev else R.string.card_rewards_cycle_same,
                            c.statementDate.format(DATE)
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        stringResource(
                            R.string.card_rewards_cycle_totals,
                            c.reported?.let { CurrencyFormatter.formatCurrency(it, currency) } ?: "–",
                            CurrencyFormatter.formatCurrency(c.estimate, currency)
                        ),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(stringResource(R.string.card_rewards_cycle_sources, c.counted.size, fromSms, fromStatement), style = MaterialTheme.typography.bodySmall)
                    if (c.missingRows.isNotEmpty()) {
                        Text(
                            stringResource(R.string.card_rewards_cycle_missing, c.missingRows.size),
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error
                        )
                        c.missingRows.forEach { m ->
                            Text(
                                "${java.time.LocalDate.ofEpochDay(m.day).format(DATE)} · ${m.amount.toBigDecimalOrNull()?.let { CurrencyFormatter.formatCurrency(it, currency) } ?: m.amount} · ${m.desc} · ${m.points}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    TextButton(onClick = { all = !all }) {
                        Text(stringResource(if (all) R.string.card_rewards_tab_merchants else R.string.card_rewards_tab_transactions))
                    }
                }
                if (all) {
                    items(c.counted.sortedBy { it.transaction.dateTime }) { RewardRow(it) }
                } else {
                    items(c.counted.byMerchant()) { MerchantRow(it) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.card_rewards_close)) } }
    )
}
