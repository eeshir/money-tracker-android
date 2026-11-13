package com.moneytracker.app.presentation.rewards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moneytracker.app.R
import com.moneytracker.app.data.rewards.TransactionReward
import com.moneytracker.app.ui.components.BrandIcon
import com.moneytracker.app.ui.theme.Spacing
import com.moneytracker.app.utils.CurrencyFormatter
import com.moneytracker.app.utils.sumByCurrency
import java.time.format.DateTimeFormatter

enum class RewardSort { NEWEST, HIGHEST, MERCHANT }

private val SHORT_DATE = DateTimeFormatter.ofPattern("d MMM")

private class RewardRowItem(val card: CardRewardsItem, val reward: TransactionReward)

private fun cardTitle(item: CardRewardsItem): String {
    val name = item.program?.name?.ifBlank { null } ?: item.card.nickname ?: item.card.bankName
    return "$name ····${item.card.cardLast4}"
}

@Composable
private fun periodLabel(p: RewardPeriod): String = stringResource(
    when (p) {
        RewardPeriod.THIS_MONTH -> R.string.card_rewards_period_month
        RewardPeriod.LAST_3_MONTHS -> R.string.card_rewards_period_3m
        RewardPeriod.ALL -> R.string.card_rewards_period_all
    }
)

/**
 * The simple Card rewards screen: one number up top, a card and period filter, a tile per card, and the purchases with
 * the reward each earned, sortable. The detail (rates, statement checks, cycles) lives in the advanced view.
 */
fun LazyListScope.simpleRewards(
    items: List<CardRewardsItem>,
    period: RewardPeriod,
    onPeriod: (RewardPeriod) -> Unit,
    selectedCardId: Long?,
    onSelectCard: (Long?) -> Unit,
    sort: RewardSort,
    onSort: (RewardSort) -> Unit,
    earningOnly: Boolean,
    onEarningOnly: (Boolean) -> Unit,
    onEdit: (CardRewardsItem) -> Unit,
    onTransactionClick: (Long) -> Unit,
    onShowAdvanced: () -> Unit
) {
    // A card that was filtered to and has since gone: show them all again.
    val selected = items.firstOrNull { it.card.id == selectedCardId }
    val shown = if (selected != null) listOf(selected) else items
    val rows = shown.flatMap { c -> c.rewards.map { RewardRowItem(c, it) } }
        .filter { !earningOnly || it.reward.reward.signum() != 0 }
        .let { list ->
            when (sort) {
                RewardSort.NEWEST -> list.sortedByDescending { it.reward.transaction.dateTime }
                RewardSort.HIGHEST -> list.sortedByDescending { it.reward.reward }
                RewardSort.MERCHANT -> list.sortedBy { it.reward.transaction.merchantName.lowercase() }
            }
        }

    // The big number: never summed across currencies.
    item {
        val total = shown.flatMap { it.rewards }.sumByCurrency({ it.transaction.currency }, { it.reward })
        val purchases = shown.sumOf { c -> c.rewards.count { !it.refund && !it.cashbackCredit } }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.rewards_hero_title, periodLabel(period)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    CurrencyFormatter.formatByCurrency(total),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    stringResource(R.string.rewards_hero_subtitle, purchases),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }

    // Filters: period, then card.
    item {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                RewardPeriod.values().forEach { p ->
                    FilterChip(selected = p == period, onClick = { onPeriod(p) }, label = { Text(periodLabel(p)) })
                }
            }
            if (items.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    FilterChip(selected = selected == null, onClick = { onSelectCard(null) }, label = { Text(stringResource(R.string.rewards_all_cards)) })
                    items.forEach { c ->
                        FilterChip(selected = selected?.card?.id == c.card.id, onClick = { onSelectCard(c.card.id) }, label = { Text(cardTitle(c)) })
                    }
                }
            }
        }
    }

    // One tile per card (all cards), or the chosen card's edit shortcut.
    if (items.isEmpty()) {
        item { Text(stringResource(R.string.card_rewards_no_cards), style = MaterialTheme.typography.bodyMedium) }
    } else if (selected == null) {
        item { Text(stringResource(R.string.rewards_cards_title), style = MaterialTheme.typography.titleMedium) }
        items(items, key = { "tile-${it.card.id}" }) { c -> CardTile(c, onClick = { onSelectCard(c.card.id) }, onSetUp = { onEdit(c) }) }
    } else {
        item(key = "selected-card") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(cardTitle(selected), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { onEdit(selected) }) {
                    Text(stringResource(if (selected.program == null) R.string.rewards_set_up_rates else R.string.rewards_edit_rates))
                }
            }
        }
    }

    // Statement checks that disagree: one quiet line that leads to the detail.
    val differing = shown.sumOf { c -> c.checks.count { it.mismatch } }
    if (differing > 0) {
        item(key = "attention") {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        androidx.compose.ui.platform.LocalContext.current.resources.getQuantityString(R.plurals.rewards_attention, differing, differing),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp)
                    )
                    TextButton(onClick = onShowAdvanced) { Text(stringResource(R.string.rewards_attention_details)) }
                }
            }
        }
    }

    // The purchases, with sort and a filter.
    item(key = "list-header") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.rewards_list_title), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                FilterChip(selected = earningOnly, onClick = { onEarningOnly(!earningOnly) }, label = { Text(stringResource(R.string.rewards_earning_only)) })
                SortMenu(sort, onSort)
            }
        }
    }
    if (rows.isEmpty()) {
        item(key = "empty") {
            Text(
                stringResource(R.string.rewards_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    items(rows, key = { "row-${it.reward.transaction.id}" }) { row ->
        RewardRowView(row, showCard = items.size > 1 && selected == null, onClick = { onTransactionClick(row.reward.transaction.id) })
    }
}

@Composable
private fun CardTile(item: CardRewardsItem, onClick: () -> Unit, onSetUp: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(cardTitle(item), style = MaterialTheme.typography.titleSmall)
                // A card without rates is one quiet line; its button is on the right.
                if (item.program != null) {
                    Text(
                        stringResource(R.string.rewards_card_purchases, item.rewards.count { !it.refund && !it.cashbackCredit }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (item.program == null) {
                TextButton(onClick = onSetUp) { Text(stringResource(R.string.rewards_set_up_rates)) }
            } else {
                Text(
                    CurrencyFormatter.formatByCurrency(item.totals),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        item.capUsage.forEach { cap ->
            val currency = item.currency
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                LinearProgressIndicator(
                    progress = { cap.used.toFloat() / cap.limit.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(
                        R.string.rewards_cap_used,
                        CurrencyFormatter.formatCurrency(cap.used, currency),
                        CurrencyFormatter.formatCurrency(cap.limit, currency),
                        CurrencyFormatter.formatCurrency(cap.left, currency),
                        cap.resets.format(SHORT_DATE)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        }
    }
}

@Composable
private fun SortMenu(sort: RewardSort, onSort: (RewardSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun label(s: RewardSort) = when (s) {
        RewardSort.NEWEST -> R.string.rewards_sort_newest
        RewardSort.HIGHEST -> R.string.rewards_sort_highest
        RewardSort.MERCHANT -> R.string.rewards_sort_merchant
    }
    Column {
        TextButton(onClick = { open = true }) { Text(stringResource(label(sort)) + " ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            RewardSort.values().forEach { s ->
                DropdownMenuItem(text = { Text(stringResource(label(s))) }, onClick = { onSort(s); open = false })
            }
        }
    }
}

@Composable
private fun RewardRowView(row: RewardRowItem, showCard: Boolean, onClick: () -> Unit) {
    val r = row.reward
    val tx = r.transaction
    fun money(v: java.math.BigDecimal) = CurrencyFormatter.formatCurrency(v, tx.currency)

    val (text, color) = when {
        r.cashbackCredit -> "+" + money(r.reward) to MaterialTheme.colorScheme.primary
        r.excluded || r.reward.signum() == 0 -> "—" to MaterialTheme.colorScheme.onSurfaceVariant
        r.reward.signum() < 0 -> "−" + money(r.reward.abs()) to MaterialTheme.colorScheme.error
        else -> "+" + money(r.reward) to MaterialTheme.colorScheme.primary
    }
    val tag = when {
        r.cashbackCredit -> stringResource(R.string.rewards_row_bank_credit)
        r.refund -> stringResource(R.string.rewards_row_refund)
        r.excluded -> stringResource(R.string.rewards_row_no_reward)
        else -> r.ratePercent.stripTrailingZeros().toPlainString() + "%" +
            if (r.statementDay != null) " · " + stringResource(R.string.rewards_row_statement) else ""
    }
    val parts = listOfNotNull(
        tx.dateTime.format(SHORT_DATE),
        if (showCard) "····${row.card.card.cardLast4}" else null,
        money(tx.amount),
        tag,
        if (r.capped) stringResource(R.string.rewards_row_capped) else null
    )
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { BrandIcon(merchantName = tx.merchantName, category = tx.category, size = 40.dp) },
        headlineContent = { Text(tx.merchantName, maxLines = 1) },
        supportingContent = { Text(parts.joinToString(" · "), maxLines = 1, style = MaterialTheme.typography.bodySmall) },
        trailingContent = { Text(text, color = color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
    )
}
