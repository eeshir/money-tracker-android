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

@HiltViewModel
class TransactionRewardViewModel @Inject constructor(
    private val store: CardRewardStore,
    private val statementRewards: com.moneytracker.app.data.statement.StatementRewardStore,
    private val dao: TransactionDao,
    private val overrideStore: com.moneytracker.app.data.rewards.RewardOverrideStore
) : ViewModel() {
    val overrides = overrideStore.rates

    /** Sets this purchase's own reward rate (percent); null goes back to the card's rate. */
    fun setRate(transactionId: Long, ratePercent: String?) = overrideStore.set(transactionId, ratePercent)

    /** The reward on [tx] from its card's saved rates, or null when it is not a rated card's transaction. */
    suspend fun rewardFor(tx: TransactionEntity): TransactionReward? {
        val bank = tx.bankName ?: return null
        val last4 = tx.accountNumber ?: return null
        val program = store.get(bank, last4) ?: return null
        val mine = statementRewards.entries.value.filter { it.cardLast4 == last4 && it.bankName.trim().equals(bank.trim(), ignoreCase = true) }
        val cardTransactions = dao.getTransactionsForAccountStrict(bank, last4)
        return RewardCalculator.rewardFor(
            program, cardTransactions, tx.id, RewardCalculator.cycleDay(mine), overrideStore.rates.value,
            com.moneytracker.app.data.rewards.ReportedRewards.of(com.moneytracker.app.data.rewards.CardRewardPresets.withDefaults(program), mine, cardTransactions)
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
