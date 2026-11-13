package com.moneytracker.app.presentation.rewards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneytracker.app.data.database.dao.TransactionDao
import com.moneytracker.app.data.database.entity.CardEntity
import com.moneytracker.app.data.database.entity.CardType
import com.moneytracker.app.data.repository.CardRepository
import com.moneytracker.app.data.rewards.CardRewardProgram
import com.moneytracker.app.data.rewards.CardRewardStore
import com.moneytracker.app.data.rewards.RewardCalculator
import com.moneytracker.app.data.rewards.RewardRule
import com.moneytracker.app.data.rewards.ReportedRewards
import com.moneytracker.app.data.rewards.StatementChecker
import kotlinx.coroutines.launch
import com.moneytracker.app.data.rewards.TransactionReward
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import com.moneytracker.app.utils.sumByCurrency
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class CardRewardsItem(
    val card: CardEntity,
    /** Null until the person sets the card's rates; rewards are then not estimated. */
    val program: CardRewardProgram?,
    val rewards: List<TransactionReward>,
    /** Each saved statement's reported rewards against the estimate for its cycle. */
    val checks: List<com.moneytracker.app.data.rewards.StatementCheck> = emptyList(),
    /** Cap used so far in the current statement cycle, per capped rule. */
    val capUsage: List<com.moneytracker.app.data.rewards.CapUsage> = emptyList()
) {
    val currency: String get() = card.currency
    /** This card's rewards, totalled per currency (a card can have foreign-currency purchases); never added across currencies. */
    val totals: Map<String, com.moneytracker.app.utils.Money> get() = rewards.sumByCurrency({ it.transaction.currency }, { it.reward })
}

enum class RewardPeriod {
    THIS_MONTH, LAST_3_MONTHS, ALL;

    /** First day included, or null for all time. */
    fun start(): java.time.LocalDate? = when (this) {
        THIS_MONTH -> java.time.LocalDate.now().withDayOfMonth(1)
        LAST_3_MONTHS -> java.time.LocalDate.now().withDayOfMonth(1).minusMonths(2)
        ALL -> null
    }
}

/** Totals per currency, never added across currencies. */
data class RewardsSummary(
    val configuredCards: Int = 0,
    val totalCards: Int = 0,
    val thisMonth: Map<String, com.moneytracker.app.utils.Money> = emptyMap(),
    val last3Months: Map<String, com.moneytracker.app.utils.Money> = emptyMap()
)

/** One merchant's spend on a card and what it earned, for spotting spends that earn less than expected. */
data class MerchantRewardLine(
    val merchant: String,
    val currency: String,
    val count: Int,
    val spend: java.math.BigDecimal,
    val reward: java.math.BigDecimal,
    /** True when every purchase there earned only the base rate or nothing. */
    val baseOnly: Boolean
)

fun List<TransactionReward>.byMerchant(): List<MerchantRewardLine> =
    groupBy { it.transaction.merchantName to it.transaction.currency }.map { (key, rows) ->
        MerchantRewardLine(
            merchant = key.first,
            currency = key.second,
            count = rows.size,
            spend = rows.fold(java.math.BigDecimal.ZERO) { acc, r -> if (r.refund) acc - r.transaction.amount else acc + r.transaction.amount },
            reward = rows.fold(java.math.BigDecimal.ZERO) { acc, r -> acc + r.reward },
            baseOnly = rows.all { it.ruleLabel == null }
        )
    }.sortedByDescending { it.spend }

@HiltViewModel
class CardRewardsViewModel @Inject constructor(
    cards: CardRepository,
    private val transactions: TransactionDao,
    refundAllocations: com.moneytracker.app.data.database.dao.RefundAllocationDao,
    private val store: CardRewardStore,
    private val statementRewards: com.moneytracker.app.data.statement.StatementRewardStore,
    private val uiPrefs: com.moneytracker.app.data.rewards.RewardsUiPrefs,
    overrideStore: com.moneytracker.app.data.rewards.RewardOverrideStore,
    private val statements: com.moneytracker.app.data.statement.ImportStatementUseCase
) : ViewModel() {

    init {
        // Read the reward totals off statements already saved on the phone.
        viewModelScope.launch {
            try { statements.refreshStatementRewards() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    private val programs = MutableStateFlow(store.all())

    /** Whether the advanced Card rewards screen is on (the simple one is the default). */
    val advanced: StateFlow<Boolean> = uiPrefs.advanced

    fun setAdvanced(value: Boolean) = uiPrefs.setAdvanced(value)

    /** Card purchases that are in twice, from an SMS and from a statement. */
    val duplicates: StateFlow<List<com.moneytracker.app.data.statement.StatementDuplicate>> =
        combine(transactions.getAllTransactions(), statementRewards.entries) { txns, entries ->
            // The statement copy of an SMS purchase, plus extra SMS alerts a day off the statement's own row.
            (com.moneytracker.app.data.statement.StatementDuplicates.find(txns) +
                com.moneytracker.app.data.statement.StatementDuplicates.findAdjacent(txns, entries)).distinctBy { it.drop.id }
        }
            .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Removes the statement copy of each duplicate, keeping the SMS row. */
    fun mergeDuplicates() {
        val found = duplicates.value
        viewModelScope.launch(Dispatchers.IO) { found.forEach { transactions.softDeleteTransaction(it.drop.id) } }
    }

    private val _period = MutableStateFlow(RewardPeriod.LAST_3_MONTHS)
    val period: StateFlow<RewardPeriod> = _period

    fun setPeriod(p: RewardPeriod) { _period.value = p }

    /** Every transaction with the refund links the person made: refund id to (purchase id, amount). */
    private val transactionsWithLinks = combine(transactions.getAllTransactions(), refundAllocations.observeAll()) { txns, allocations ->
        txns to allocations.groupBy({ it.refundId }, { it.expenseId to it.amount })
    }

    /** Every card's rewards over all time. Caps are applied over the full history, then the period is cut. */
    private val allItems: StateFlow<List<CardRewardsItem>> =
        combine(cards.getAllActiveCards(), transactionsWithLinks, programs, statementRewards.entries, overrideStore.rates) { allCards, (txns, links), saved, stmts, overrides ->
            allCards.filter { it.cardType == CardType.CREDIT }.map { card ->
                val program = saved.firstOrNull { it.key == CardRewardProgram.keyFor(card.bankName, card.cardLast4) }?.let(com.moneytracker.app.data.rewards.CardRewardPresets::withDefaults)
                val mine = stmts.filter { it.cardLast4 == card.cardLast4 && it.bankName.trim().equals(card.bankName.trim(), ignoreCase = true) }
                val cycleDay = RewardCalculator.cycleDay(mine)
                // The check compares each statement with what the rules alone predict; what is shown uses the
                // rewards the statement printed for the purchases it lists.
                val predicted = program?.let { RewardCalculator.compute(it, txns, cycleDay, overrides, refundLinks = links) }.orEmpty()
                val reported = program?.let { ReportedRewards.of(it, mine, txns) }
                val rewards = program?.let { RewardCalculator.compute(it, txns, cycleDay, overrides, reported, links) }.orEmpty()
                // Which statement printed each purchase, so the check puts it in that statement's cycle.
                val statementOf = rewards.associate { it.transaction.id to it.statementDay }
                val predictedByStatement = predicted.map { it.copy(statementDay = statementOf[it.transaction.id]) }
                // Statement rows no transaction matched (payments and zero-reward credits are not purchases).
                val missing = reported?.unmatched().orEmpty().filterNot { it.credit && (it.points.toBigDecimalOrNull()?.signum() ?: 0) == 0 }
                CardRewardsItem(card, program, rewards, program?.let { StatementChecker.check(it, predictedByStatement, mine, missing, card.currency) }.orEmpty(),
                    program?.let { RewardCalculator.capUsage(it, rewards, cycleDay) }.orEmpty())
            }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The cards with rewards limited to the chosen period. */
    val items: StateFlow<List<CardRewardsItem>> =
        combine(allItems, _period) { all, p ->
            val from = p.start()
            all.map { it.copy(rewards = it.rewards.filter { r -> from == null || !r.transaction.dateTime.toLocalDate().isBefore(from) }) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** What the Home card shows: this month and the last three months, per currency. */
    val summary: StateFlow<RewardsSummary> = allItems.map { all ->
        val rewards = all.filter { it.program != null }.flatMap { it.rewards }
        fun since(d: java.time.LocalDate) =
            rewards.filter { !it.transaction.dateTime.toLocalDate().isBefore(d) }
                .sumByCurrency({ it.transaction.currency }, { it.reward })
        RewardsSummary(
            configuredCards = all.count { it.program != null },
            totalCards = all.size,
            thisMonth = since(RewardPeriod.THIS_MONTH.start()!!),
            last3Months = since(RewardPeriod.LAST_3_MONTHS.start()!!)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RewardsSummary())

    fun save(card: CardEntity, name: String, baseRate: String, baseCap: String, pointValue: String, rulesText: String, excludedText: String) {
        val existing = store.get(card.bankName, card.cardLast4)
        store.save(
            CardRewardProgram(
                bankName = card.bankName,
                cardLast4 = card.cardLast4,
                name = name.trim(),
                baseRatePercent = baseRate.trim().ifEmpty { "0" },
                baseMonthlyCap = baseCap.trim().ifEmpty { null },
                pointValue = pointValue.trim().ifEmpty { null },
                rules = parseRules(rulesText),
                excludedKeywords = splitKeywords(excludedText),
                // Not editable in the form, so keep what the card already had, or the suggestion's.
                noRewardForeignFrom = existing?.noRewardForeignFrom
                    ?: com.moneytracker.app.data.rewards.CardRewardPresets.forCard(card.bankName)?.noRewardForeignFrom
                ,
                laterExcludedKeywords = existing?.laterExcludedKeywords?.takeIf { it.isNotEmpty() }
                    ?: com.moneytracker.app.data.rewards.CardRewardPresets.forCard(card.bankName)?.laterExcludedKeywords.orEmpty(),
                laterExcludedFrom = existing?.laterExcludedFrom
                    ?: com.moneytracker.app.data.rewards.CardRewardPresets.forCard(card.bankName)?.laterExcludedFrom
            )
        )
        programs.value = store.all()
    }

    companion object {
        /**
         * One rule per line, columns separated by `|`:
         * `label | keywords | rate % | cap | earlier cap | earlier until | extra keywords | extra from | except keywords | rate above cap | earlier rate above cap`
         * (everything after the rate is optional; keywords are comma-separated, dates are YYYY-MM-DD).
         */
        fun parseRules(text: String): List<RewardRule> = text.lines().mapNotNull { line ->
            val parts = line.split('|').map { it.trim() }
            if (parts.size < 3 || parts[2].trimEnd('%').toBigDecimalOrNull() == null) return@mapNotNull null
            RewardRule(
                label = parts[0], keywords = splitKeywords(parts[1]), ratePercent = parts[2].trimEnd('%').trim(),
                monthlyCap = parts.getOrNull(3)?.ifEmpty { null },
                earlierMonthlyCap = parts.getOrNull(4)?.ifEmpty { null }, earlierUntil = parts.getOrNull(5)?.ifEmpty { null },
                extraKeywords = splitKeywords(parts.getOrNull(6).orEmpty()), extraFrom = parts.getOrNull(7)?.ifEmpty { null },
                exceptKeywords = splitKeywords(parts.getOrNull(8).orEmpty()),
                aboveCapPercent = parts.getOrNull(9)?.ifEmpty { null }, earlierAboveCapPercent = parts.getOrNull(10)?.ifEmpty { null }
            )
        }

        fun rulesToText(rules: List<RewardRule>): String = rules.joinToString("\n") { r ->
            val columns = listOf(
                r.label, r.keywords.joinToString(", "), r.ratePercent, r.monthlyCap.orEmpty(),
                r.earlierMonthlyCap.orEmpty(), r.earlierUntil.orEmpty(),
                r.extraKeywords.joinToString(", "), r.extraFrom.orEmpty(), r.exceptKeywords.joinToString(", "),
                r.aboveCapPercent.orEmpty(), r.earlierAboveCapPercent.orEmpty()
            )
            columns.dropLastWhile { it.isEmpty() }.joinToString(" | ")
        }

        fun splitKeywords(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
