package com.moneytracker.app.presentation.rewards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.moneytracker.app.data.rewards.RateChoices
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.moneytracker.app.R
import com.moneytracker.app.data.database.dao.TransactionDao
import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.rewards.CardRewardStore
import com.moneytracker.app.data.rewards.RewardCalculator
import com.moneytracker.app.data.rewards.TransactionReward
import com.moneytracker.app.utils.CurrencyFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.first

@HiltViewModel
class TransactionRewardViewModel @Inject constructor(
    private val store: CardRewardStore,
    private val statementRewards: com.moneytracker.app.data.statement.StatementRewardStore,
    private val dao: TransactionDao,
    private val overrideStore: com.moneytracker.app.data.rewards.RewardOverrideStore,
    private val refundAllocations: com.moneytracker.app.data.database.dao.RefundAllocationDao
) : ViewModel() {
    val overrides = overrideStore.rates

    /** The two rates this purchase can be switched between, when its card has such a pair. */
    fun choicesFor(tx: TransactionEntity): RateChoices.Choice? {
        val program = store.get(tx.bankName ?: return null, tx.accountNumber ?: return null) ?: return null
        return RateChoices.forPurchase(program, tx)
    }

    /** Sets this purchase's own reward rate (percent); null goes back to the card's rate. */
    fun setRate(transactionId: Long, ratePercent: String?) = overrideStore.set(transactionId, ratePercent)

    /** The reward on [tx] from its card's saved rates, or null when it is not a rated card's transaction. */
    suspend fun rewardFor(tx: TransactionEntity): TransactionReward? {
        val bank = tx.bankName ?: return null
        val last4 = tx.accountNumber ?: return null
        val program = store.get(bank, last4) ?: return null
        val mine = statementRewards.entries.value.filter { it.cardLast4 == last4 && it.bankName.trim().equals(bank.trim(), ignoreCase = true) }
        // The same inputs as the Card rewards screen, so a purchase reads the same in both: a refund noted by hand
        // has no account, and linked refunds decide what each purchase keeps under a cap.
        val transactions = dao.getAllTransactions().first()
        val links = refundAllocations.observeAll().first().groupBy({ it.refundId }, { it.expenseId to it.amount })
        val full = com.moneytracker.app.data.rewards.CardRewardPresets.withDefaults(program)
        return RewardCalculator.rewardFor(
            full, transactions, tx.id, RewardCalculator.cycleDay(mine), overrideStore.rates.value,
            com.moneytracker.app.data.rewards.ReportedRewards.of(full, mine, transactions), links
        )
    }
}

/** A line on the transaction detail screen: the cashback/reward this transaction earned (or gave back). */
@Composable
fun TransactionRewardCard(
    transaction: TransactionEntity,
    modifier: Modifier = Modifier,
    viewModel: TransactionRewardViewModel = hiltViewModel()
) {
    var reward by remember(transaction.id) { mutableStateOf<TransactionReward?>(null) }
    var editing by remember { mutableStateOf(false) }
    val overrides by viewModel.overrides.collectAsStateWithLifecycle()
    LaunchedEffect(transaction.id, transaction.amount, transaction.merchantName, transaction.transactionType, overrides) {
        reward = viewModel.rewardFor(transaction)
    }
    val r = reward ?: return
    if (editing) {
        RateOverrideDialog(
            current = r.ratePercent.stripTrailingZeros().toPlainString(),
            hasOverride = overrides.containsKey(transaction.id),
            onDismiss = { editing = false }
        ) { viewModel.setRate(transaction.id, it?.takeIf { v -> v.toBigDecimalOrNull() != null }); editing = false }
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(if (r.refund) R.string.card_rewards_detail_refund else R.string.card_rewards_detail_title),
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                CurrencyFormatter.formatCurrency(r.reward, r.transaction.currency),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                when {
                    r.excluded -> stringResource(R.string.card_rewards_excluded)
                    else -> "${r.ruleLabel ?: stringResource(R.string.card_rewards_base_rate)} · ${r.ratePercent.stripTrailingZeros().toPlainString()}%" +
                        if (r.capped) " · " + stringResource(R.string.card_rewards_capped) else ""
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Text(stringResource(R.string.card_rewards_detail_estimate), style = MaterialTheme.typography.bodySmall)
            val choice = remember(transaction.id, transaction.merchantName, transaction.category) { viewModel.choicesFor(transaction) }
            if (choice != null) {
                val chosen = overrides[transaction.id]?.toBigDecimalOrNull()
                val fromTataStatement = choice.kind == RateChoices.Kind.TATA_PARTNER && r.statementDay != null
                val selected = when {
                    chosen != null -> choice.rates.firstOrNull { it.compareTo(chosen) == 0 }
                    // The row alone shows 1%; the card's other 1% is in the statement's bonus total.
                    fromTataStatement -> choice.rates.first()
                    else -> RateChoices.selected(choice, r.ratePercent)
                }
                if (fromTataStatement) {
                    Text(stringResource(R.string.card_rewards_tata_statement_note), style = MaterialTheme.typography.bodySmall)
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    choice.rates.forEachIndexed { i, rate ->
                        val label = when (choice.kind) {
                            RateChoices.Kind.AMAZON ->
                                if (i == 0) R.string.card_rewards_choice_amazon_shopping else R.string.card_rewards_choice_amazon_voucher
                            RateChoices.Kind.TATA_PARTNER ->
                                if (i == 0) R.string.card_rewards_choice_tata_card else R.string.card_rewards_choice_tata_app
                        }
                        SegmentedButton(
                            selected = rate == selected,
                            onClick = { viewModel.setRate(transaction.id, rate.toPlainString()) },
                            shape = SegmentedButtonDefaults.itemShape(i, choice.rates.size)
                        ) {
                            Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            TextButton(onClick = { editing = true }) { Text(stringResource(R.string.card_rewards_change_rate)) }
        }
    }
}

@Composable
private fun RateOverrideDialog(current: String, hasOverride: Boolean, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var rate by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.card_rewards_change_rate)) },
        text = {
            OutlinedTextField(
                rate, { rate = it }, singleLine = true,
                label = { Text(stringResource(R.string.card_rewards_override_label)) },
                supportingText = { Text(stringResource(R.string.card_rewards_override_hint)) }
            )
        },
        confirmButton = { TextButton(onClick = { onSave(rate.trim().ifEmpty { null }) }) { Text(stringResource(R.string.card_rewards_save)) } },
        dismissButton = {
            if (hasOverride) TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.card_rewards_override_reset)) }
            else TextButton(onClick = onDismiss) { Text(stringResource(R.string.card_rewards_cancel)) }
        }
    )
}
