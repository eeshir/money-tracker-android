package com.moneytracker.app.data.rewards

import android.content.Context
import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.mapper.isCreditCardPayment
import com.moneytracker.app.data.database.entity.TransactionType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A rate for spends whose merchant, category or description contains any of
 * [keywords] (case-insensitive). Rules are tried in order; the first match wins.
 * Rates are percentages kept as text so no precision is lost in storage.
 */
@Serializable
data class RewardRule(
    val label: String = "",
    val keywords: List<String> = emptyList(),
    val ratePercent: String = "0",
    /** Most reward this rule can earn in a calendar month, in the card's currency. Null = no cap. */
    val monthlyCap: String? = null,
    /** A different cap for purchases before [earlierUntil] (an ISO date), when the card's terms changed. */
    val earlierMonthlyCap: String? = null,
    val earlierUntil: String? = null,
    /** More keywords that count only for purchases on or after [extraFrom] (an ISO date): a category added later. */
    val extraKeywords: List<String> = emptyList(),
    val extraFrom: String? = null,
    /** A purchase matching any of these never matches this rule (e.g. merchants carved out of a category). */
    val exceptKeywords: List<String> = emptyList(),
    /** Rate on the part of a purchase beyond the monthly cap (percent); null = nothing. Before [earlierUntil] it is [earlierAboveCapPercent]. */
    val aboveCapPercent: String? = null,
    val earlierAboveCapPercent: String? = null,
    /** A limited-time offer: from [offerFrom] to [offerUntil] (ISO dates, both included) these match even if excepted. */
    val offerKeywords: List<String> = emptyList(),
    val offerFrom: String? = null,
    val offerUntil: String? = null
)

/** How one card earns cashback/rewards. Values come from the card's own terms, entered by the person. */
@Serializable
data class CardRewardProgram(
    val bankName: String,
    val cardLast4: String,
    val name: String = "",
    /** Rate for spends no rule matches. */
    val baseRatePercent: String = "0",
    val baseMonthlyCap: String? = null,
    /** Rupees one point is worth, when the card earns points and the statement reports points. */
    val pointValue: String? = null,
    val rules: List<RewardRule> = emptyList(),
    /** Spends matching any of these earn nothing (fuel, rent, wallet loads, EMIs...). */
    val excludedKeywords: List<String> = emptyList(),
    /** From this ISO date, purchases not in rupees earn nothing (a card whose terms dropped foreign-spend rewards). */
    val noRewardForeignFrom: String? = null,
    /** More no-reward keywords that apply only from [laterExcludedFrom] (an ISO date). */
    val laterExcludedKeywords: List<String> = emptyList(),
    val laterExcludedFrom: String? = null
) {
    val key: String get() = keyFor(bankName, cardLast4)

    companion object {
        fun keyFor(bankName: String, cardLast4: String) = "${bankName.trim().lowercase()}|$cardLast4"
    }
}

/** What one purchase earned, and why. [ruleLabel] is null for the base rate. */
data class TransactionReward(
    val transaction: TransactionEntity,
    val reward: BigDecimal,
    val ratePercent: BigDecimal,
    val ruleLabel: String?,
    val excluded: Boolean,
    /** True when a monthly cap trimmed the reward. */
    val capped: Boolean,
    /** A refund taking back the reward its purchase earned (negative [reward]). */
    val refund: Boolean = false,
    /** The statement (epoch day) that printed this purchase, when one did: it decides which cycle the purchase is in. */
    val statementDay: Long? = null,
    /** The row is a cashback the bank credited (not an estimate from a rate), e.g. HDFC SmartBuy bonus. */
    val cashbackCredit: Boolean = false,
    /** The part of [reward] paid as SmartBuy cashback in rupees, apart from the card's points. */
    val cashbackPart: BigDecimal = BigDecimal.ZERO
)

/** Reward earned so far under one rule's cap in the current cycle; the cycle ends (and the cap resets after) [resets]. */
data class CapUsage(
    val label: String,
    val used: BigDecimal,
    val limit: BigDecimal,
    val resets: java.time.LocalDate,
    /** True when the cap runs by calendar month rather than by statement cycle. */
    val calendarMonth: Boolean = false
) {
    val left: BigDecimal get() = limit - used
}

/** Whether a purchase described by [haystack] on [day] falls under this rule, given its dated keywords and exceptions. */
internal fun RewardRule.matches(haystack: String, day: java.time.LocalDate): Boolean {
    fun matched(words: List<String>) = words.filter { it.isNotBlank() && haystack.contains(it.trim().lowercase()) }
    fun date(text: String?) = text?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
    val starts = date(offerFrom)
    val ends = date(offerUntil)
    if (starts != null && ends != null && !day.isBefore(starts) && !day.isAfter(ends) && matched(offerKeywords).isNotEmpty()) return true
    val from = date(extraFrom)
    val hits = matched(keywords) + (if (from != null && !day.isBefore(from)) matched(extraKeywords) else emptyList())
    if (hits.isEmpty()) return false
    val excepted = matched(exceptKeywords)
    // An exception (Amazon) is overridden by a longer keyword that contains it (Amazon Fresh).
    return excepted.isEmpty() || hits.any { h -> excepted.any { e -> h.lowercase().contains(e.lowercase()) } }
}

/** The percent earned on the part beyond the cap on [day]. */
internal fun RewardRule.aboveCapOn(day: java.time.LocalDate): java.math.BigDecimal {
    val until = earlierUntil?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
    val text = if (until != null && day.isBefore(until)) earlierAboveCapPercent else aboveCapPercent
    return text?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
}

/** The monthly cap that applied on [day]. */
internal fun RewardRule.capOn(day: java.time.LocalDate): String? {
    val until = earlierUntil?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
    return if (until != null && day.isBefore(until) && earlierMonthlyCap != null) earlierMonthlyCap else monthlyCap
}

object RewardCalculator {

    /**
     * Estimated reward for each purchase of [program]'s card in [transactions], oldest first
     * so monthly caps fill in the order the money was spent. Purchases are the card's
     * [TransactionType.CREDIT] rows. A refund (money back from a merchant) takes back the reward at the same rate; bill payments and cashback credits are ignored.
     */
    fun compute(
        program: CardRewardProgram,
        transactions: List<TransactionEntity>,
        cycleDay: Int? = null,
        overrides: Map<Long, String> = emptyMap(),
        reported: ReportedRewards? = null,
        /** Refund id to the (purchase id, amount) pairs the person linked it to. */
        refundLinks: Map<Long, List<Pair<Long, BigDecimal>>> = emptyMap()
    ): List<TransactionReward> {
        // (rule index, -1 = base) + month -> reward so far. For a capped rule it is what was earned before the cap,
        // so refunds come off that first: a cycle still over its cap after them loses nothing, in whatever order.
        val used = HashMap<Pair<Int, YearMonth>, BigDecimal>()
        /** What a capped rule pays when [earned] was earned before its cap: the cap, plus the above-cap rate on the rest. */
        fun paid(earned: BigDecimal, limit: BigDecimal, rate: BigDecimal, above: BigDecimal): BigDecimal =
            if (earned <= limit) earned
            else limit + if (rate.signum() == 0) BigDecimal.ZERO else (earned - limit).multiply(above).divide(rate, 2, RoundingMode.HALF_UP)
        val byId = transactions.associateBy { it.id }
        val done = HashMap<Long, TransactionReward>() // what each transaction earned, so a linked refund can take back its purchase's share
        // Money paid into some other account (say, someone paying back their share) never reached the card, so the
        // card's bank takes nothing back for it. An entry with no account is the person's own note of a card refund.
        fun TransactionEntity.linkedToProgram() = accountNumber.isNullOrBlank() &&
            refundLinks[id]?.any { byId[it.first]?.belongsTo(program) == true } == true

        /**
         * A refund the person linked to purchases (a manual entry, or a refund SMS the app could not tie to the card)
         * takes back the share of each linked purchase's reward that the refunded part covers, under that purchase's
         * own rule: the refund's own merchant and category say nothing about what the purchase earned.
         */
        fun linkedRefund(tx: TransactionEntity): TransactionReward? {
            val links = refundLinks[tx.id]?.takeIf { tx.transactionType == TransactionType.INCOME && it.isNotEmpty() } ?: return null
            var back = BigDecimal.ZERO
            var label: String? = null
            var rate = BigDecimal.ZERO
            var counted = false
            for ((expenseId, amount) in links) {
                val expense = byId[expenseId]?.takeIf { it.belongsTo(program) } ?: continue
                val earned = done[expenseId]?.takeIf { !it.excluded && (it.reward.signum() > 0 || it.capped) && expense.amount.signum() > 0 } ?: continue
                var share = amount.min(expense.amount).multiply(earned.reward).divide(expense.amount, 2, RoundingMode.HALF_UP)
                val index = program.rules.indexOfFirst { it.label == earned.ruleLabel }
                if (index >= 0) {
                    val day = tx.dateTime.toLocalDate()
                    val slot = index to cycleOf(day, capDay(program, cycleDay))
                    val soFar = used[slot] ?: BigDecimal.ZERO
                    val limit = program.rules[index].capOn(day)?.toBigDecimalOrNull()
                    if (limit == null) used[slot] = (soFar - share).max(BigDecimal.ZERO)
                    else {
                        // In the purchase's own cycle, a purchase the cap trimmed gives back what it earned before the cap.
                        val sameCycle = cycleOf(expense.dateTime.toLocalDate(), capDay(program, cycleDay)) == slot.second
                        val before = if (earned.capped && sameCycle) {
                            amount.min(expense.amount).multiply(earned.ratePercent).divide(HUNDRED, 2, RoundingMode.HALF_UP)
                        } else share
                        val above = program.rules[index].aboveCapOn(day)
                        used[slot] = soFar - before
                        share = paid(soFar, limit, earned.ratePercent, above) - paid(soFar - before, limit, earned.ratePercent, above)
                    }
                }
                back += share
                counted = true
                if (label == null) { label = earned.ruleLabel; rate = earned.ratePercent }
            }
            if (!counted) return TransactionReward(tx, BigDecimal.ZERO, BigDecimal.ZERO, null, excluded = true, capped = false, refund = true)
            return TransactionReward(tx, back.negate(), rate, label, excluded = false, capped = false, refund = true)
        }

        // HDFC SmartBuy: the 5% is shown on the purchase at once; the bank's credit for it, when it arrives,
        // only confirms it and is not counted a second time.
        val smartBuy = SmartBuy.appliesTo(program)
        val marked = overrides.filterValues { it.toBigDecimalOrNull()?.compareTo(SmartBuy.RATE_PERCENT) == 0 }.keys
        val smartBuyPaid = if (smartBuy) SmartBuy.pair(transactions.filter { it.belongsTo(program) }, marked) else emptyMap()
        val smartBuyUsed = HashMap<YearMonth, BigDecimal>() // calendar month -> SmartBuy cashback so far
        val countedCredits = smartBuyPaid.values.toHashSet()
        // A refund the bank put against an EMI loan takes back no reward.
        val loanRefunds = com.moneytracker.app.data.statement.EmiConversions.refundsPaidIntoLoan(transactions.filter { it.belongsTo(program) })

        val one: (TransactionEntity) -> TransactionReward = one@{ tx ->
                val haystack = "${tx.merchantName} ${tx.category} ${tx.description.orEmpty()}".lowercase()
                val refund = tx.transactionType == TransactionType.INCOME
                val overridden = overrides[tx.id]?.toBigDecimalOrNull()
                // A reward the statement printed for this very purchase beats any rule (unless the person set a rate).
                val keepsStatement = overridden != null && RateChoices.statementKeepsCardPart(program, tx, overridden)
                val row = if (overridden == null || keepsStatement) reported?.takeRow(tx) else null
                val printed = row?.let { reported?.reward(it) }
                if (printed != null) {
                    // The Neu app's own 5% comes on top, in whole NeuCoins.
                    val app = if (keepsStatement && RateChoices.includesNeuApp(overridden!!)) {
                        tx.amount.multiply(RateChoices.NEU_APP_PERCENT).divide(HUNDRED, 0, RoundingMode.DOWN).setScale(2)
                    } else BigDecimal.ZERO
                    val total = printed + app
                    val effective = if (tx.amount.signum() == 0) BigDecimal.ZERO else total.abs().multiply(HUNDRED).divide(tx.amount, 2, RoundingMode.HALF_UP)
                    return@one TransactionReward(
                        tx, total, effective, if (app.signum() > 0) "Statement + Neu app 5%" else "Statement",
                        excluded = false, capped = false, refund = refund, statementDay = row.statement.takeIf { it > 0 }
                    )
                }
                // Fees, taxes and interest never earn a reward on any card.
                if (FEE_WORDS.any { haystack.contains(it) }) {
                    return@one TransactionReward(tx, BigDecimal.ZERO, BigDecimal.ZERO, null, excluded = true, capped = false, refund = refund)
                }
                val kind = if (smartBuy) SmartBuy.kindOf(tx) else null
                if (kind != null) {
                    val paid = tx.id in smartBuyPaid
                    val rate = overridden ?: if (paid || SmartBuy.expectsCashback(kind)) SmartBuy.RATE_PERCENT else BigDecimal.ZERO
                    // The cashback has its own cap, by calendar month; CashPoints on a partner shop are apart from it.
                    val month = YearMonth.from(tx.dateTime.toLocalDate())
                    val soFar = smartBuyUsed[month] ?: BigDecimal.ZERO
                    val due = tx.amount.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP)
                    val cashback = due.min((SmartBuy.MONTHLY_CAP - soFar).max(BigDecimal.ZERO))
                    smartBuyUsed[month] = soFar + cashback
                    val points = if (kind == SmartBuy.Kind.PARTNER && overridden == null)
                        SmartBuy.basePoints(tx.amount, program.pointValue?.toBigDecimalOrNull() ?: BigDecimal.ZERO)
                    else BigDecimal.ZERO
                    val label = when {
                        overridden != null -> "Your rate"
                        cashback < due -> "SmartBuy, monthly cap reached"
                        paid -> "SmartBuy 5%, paid by bank"
                        kind == SmartBuy.Kind.PARTNER -> "SmartBuy 5% + CashPoints"
                        kind == SmartBuy.Kind.VOUCHER_CASHBACK -> "SmartBuy 5%"
                        kind == SmartBuy.Kind.VOUCHER_DISCOUNT -> "SmartBuy instant discount, no cashback"
                        else -> "SmartBuy: rate not known yet"
                    }
                    return@one TransactionReward(
                        tx, cashback + points, rate, label, excluded = false, capped = cashback < due, refund = false,
                        cashbackPart = cashback
                    )
                }
                val laterFrom = program.laterExcludedFrom?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                val excludedWords = program.excludedKeywords + (if (laterFrom != null && !tx.dateTime.toLocalDate().isBefore(laterFrom)) program.laterExcludedKeywords else emptyList())
                if (overridden == null && excludedWords.any { it.isNotBlank() && haystack.contains(it.trim().lowercase()) }) {
                    return@one TransactionReward(tx, BigDecimal.ZERO, BigDecimal.ZERO, null, excluded = true, capped = false, refund = refund)
                }
                val day = tx.dateTime.toLocalDate()
                if (tx.isCashbackCredit()) {
                    return@one TransactionReward(
                        tx, tx.amount.setScale(2, RoundingMode.HALF_UP), BigDecimal.ZERO, "Bank cashback credit", excluded = false, capped = false, refund = false, cashbackCredit = true
                    )
                }
                val foreignCutoff = program.noRewardForeignFrom?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                if (overridden == null && foreignCutoff != null && !day.isBefore(foreignCutoff) && tx.currency != "INR") {
                    return@one TransactionReward(tx, BigDecimal.ZERO, BigDecimal.ZERO, null, excluded = true, capped = false, refund = refund)
                }
                val index = program.rules.indexOfFirst { it.matches(haystack, day) }
                val rate = overridden ?: if (index >= 0) percent(program.rules[index].ratePercent) else percent(program.baseRatePercent)
                val cap = if (index >= 0) program.rules[index].capOn(tx.dateTime.toLocalDate()) else program.baseMonthlyCap
                val slot = index to cycleOf(tx.dateTime.toLocalDate(), capDay(program, cycleDay))
                // MoneyBack+ gives its points for each full Rs 200 of a purchase; a rate set by hand is taken as it is.
                val earningSpend = if (smartBuy && overridden == null) SmartBuy.fullBlocks(tx.amount) else tx.amount
                var reward = earningSpend.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP)
                // A card that pays points pays whole points: each purchase rounds down to a point.
                program.pointValue?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.let { pv ->
                    reward = reward.divide(pv, 0, RoundingMode.DOWN).multiply(pv).setScale(2, RoundingMode.HALF_UP)
                }
                var capped = false
                val limit = cap?.toBigDecimalOrNull()
                val soFar = used[slot] ?: BigDecimal.ZERO
                if (limit == null) {
                    // A refund takes back what the purchase earned.
                    if (refund) {
                        used[slot] = (soFar - reward).max(BigDecimal.ZERO)
                        reward = reward.negate()
                    } else used[slot] = soFar + reward
                } else {
                    val now = if (refund) soFar - reward else soFar + reward
                    val above = program.rules.getOrNull(index)?.aboveCapOn(day) ?: BigDecimal.ZERO
                    used[slot] = now
                    capped = !refund && now > limit
                    reward = paid(now, limit, rate, above) - paid(soFar, limit, rate, above)
                }
                TransactionReward(tx, reward, rate, if (overridden != null) "Your rate" else program.rules.getOrNull(index)?.label, excluded = false, capped = capped, refund = refund)
            }
        return transactions
            .filter { (it.belongsTo(program) || it.linkedToProgram()) && !it.isDeleted && (it.isPurchase() || it.isRefund() || it.isCashbackCredit()) }
            .filter { it.id !in countedCredits && it.id !in loanRefunds }
            .sortedBy { it.dateTime }
            .map { tx -> (linkedRefund(tx) ?: one(tx)).also { done[tx.id] = it } }
    }

    /** The reward (or refund clawback) for transaction [id] of [program]'s card, caps considered. */
    fun rewardFor(
        program: CardRewardProgram, transactions: List<TransactionEntity>, id: Long, cycleDay: Int? = null,
        overrides: Map<Long, String> = emptyMap(), reported: ReportedRewards? = null,
        refundLinks: Map<Long, List<Pair<Long, BigDecimal>>> = emptyMap()
    ): TransactionReward? =
        compute(program, transactions, cycleDay, overrides, reported, refundLinks).firstOrNull { it.transaction.id == id }

    /**
     * The statement cycle a date falls in, named by the month its statement is dated: with statements
     * dated the 17th, the 18th of September to the 17th of October is the "October" cycle. Monthly caps
     * reset per cycle, not per calendar month. With no known statement day it is the calendar month.
     */
    internal fun cycleOf(d: java.time.LocalDate, cycleDay: Int?): YearMonth {
        val ym = YearMonth.from(d)
        if (cycleDay == null) return ym
        return if (d.dayOfMonth <= minOf(cycleDay, ym.lengthOfMonth())) ym else ym.plusMonths(1)
    }

    /**
     * How much of each capped rule's limit [rewards] (the card's rewards over all time) have used in the cycle [today]
     * falls in. Rewards a statement printed carry no rule label, so they are not counted: a cycle still open has none.
     */
    fun capUsage(
        program: CardRewardProgram, rewards: List<TransactionReward>, cycleDay: Int?,
        today: java.time.LocalDate = java.time.LocalDate.now()
    ): List<CapUsage> {
        val monthly = SmartBuy.appliesTo(program)
        val capCycleDay = capDay(program, cycleDay)
        val cycle = cycleOf(today, capCycleDay)
        val resets = cycle.atDay(minOf(capCycleDay ?: cycle.lengthOfMonth(), cycle.lengthOfMonth()))
        val smartBuyThisMonth = if (!SmartBuy.appliesTo(program)) BigDecimal.ZERO else rewards
            .filter { YearMonth.from(it.transaction.dateTime.toLocalDate()) == YearMonth.from(today) }
            .fold(BigDecimal.ZERO) { acc, r -> acc + r.cashbackPart }
            .max(BigDecimal.ZERO).min(SmartBuy.MONTHLY_CAP)
        // Discount-route vouchers pay no cashback, so they take no room here.
        val smartBuyCap = if (!monthly) emptyList() else listOf(
            CapUsage(SmartBuy.CAP_LABEL, smartBuyThisMonth, SmartBuy.MONTHLY_CAP, YearMonth.from(today).atEndOfMonth(), calendarMonth = true)
        )
        return smartBuyCap + program.rules.mapNotNull { rule ->
            val limit = rule.capOn(today)?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
            val used = rewards
                .filter { !it.excluded && !it.cashbackCredit && it.ruleLabel == rule.label && cycleOf(it.transaction.dateTime.toLocalDate(), capCycleDay) == cycle }
                .fold(BigDecimal.ZERO) { acc, r -> acc + r.reward }
                .max(BigDecimal.ZERO).min(limit)
            CapUsage(rule.label, used, limit, resets, calendarMonth = monthly)
        }
    }

    /** The day that ends a cap period: the statement day, except on MoneyBack+, whose caps run by calendar month. */
    internal fun capDay(program: CardRewardProgram, cycleDay: Int?): Int? =
        if (SmartBuy.appliesTo(program)) null else cycleDay

    /** The day of the month the card's statements are dated, from the newest statement read. */
    fun cycleDay(entries: List<com.moneytracker.app.data.statement.StatementRewardEntry>): Int? =
        entries.maxByOrNull { it.statementDay }?.statementDate?.dayOfMonth

    private fun TransactionEntity.isPurchase() = transactionType == TransactionType.CREDIT

    /**
     * Money coming back onto the card: a refund, not the bill being paid or a cashback credit.
     * Card bill payments are recognised the same way the analytics do.
     */
    private fun TransactionEntity.isRefund(): Boolean {
        if (transactionType != TransactionType.INCOME || excludedFromAnalytics) return false
        if (isCreditCardPayment(merchantName, smsBody, TransactionType.INCOME)) return false
        val text = "$merchantName ${description.orEmpty()}".lowercase()
        return !(text.contains("cashback") || text.contains("reward") || text.contains("payment") ||
            // A bonus credit is the reward itself, and an EMI conversion re-bills a purchase rather than refunding it.
            text.contains("bonus") || text.contains("smartbuy") || text.contains("emi") || text.contains("aggregator"))
    }

    /** A statement credit that is itself cashback (HDFC SmartBuy_Bonus_5per_CB...): counted as a reward, not a refund. */
    private fun TransactionEntity.isCashbackCredit(): Boolean {
        if (transactionType != TransactionType.INCOME) return false
        val text = merchantName.lowercase()
        return text.contains("smartbuy") && text.contains("bonus")
    }

    private fun TransactionEntity.belongsTo(p: CardRewardProgram) =
        accountNumber == p.cardLast4 && bankName.orEmpty().trim().equals(p.bankName.trim(), ignoreCase = true)

    private fun percent(text: String) = text.toBigDecimalOrNull()?.max(BigDecimal.ZERO) ?: BigDecimal.ZERO

    private val FEE_WORDS = listOf(
        "joining fee", "annual fee", "renewal fee", "membership fee", "membershipfee", "late fee", "late payment",
        "finance charge", "igst", "cgst", "sgst", "gst on", "interest charge"
    )

    private val HUNDRED = BigDecimal(100)
}

/** Reward programs per card, kept on the device in plain preferences (no Room migration needed). */
@Singleton
class CardRewardStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("card_reward_programs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    init { upgradeOnce() }

    /** Saved rules are the person's own copy, so a corrected suggestion reaches them only through this, once. */
    private fun upgradeOnce() {
        if (prefs.getInt(KEY_UPGRADE, 0) >= RewardRuleUpgrades.VERSION) return
        val saved = all()
        prefs.edit().apply {
            if (saved.isNotEmpty()) putString(KEY, json.encodeToString(saved.map(RewardRuleUpgrades::apply)))
            putInt(KEY_UPGRADE, RewardRuleUpgrades.VERSION)
        }.apply()
    }

    fun all(): List<CardRewardProgram> =
        prefs.getString(KEY, null)
            ?.let { runCatching { json.decodeFromString<List<CardRewardProgram>>(it) }.getOrNull() }
            .orEmpty()

    fun get(bankName: String, cardLast4: String): CardRewardProgram? =
        all().firstOrNull { it.key == CardRewardProgram.keyFor(bankName, cardLast4) }

    @Synchronized
    fun save(program: CardRewardProgram) {
        val rest = all().filterNot { it.key == program.key }
        prefs.edit().putString(KEY, json.encodeToString(rest + program)).apply()
    }

    /** Adds programs from a backup for cards that have none here yet; a program set up on this phone stays. */
    @Synchronized
    fun restore(programs: List<CardRewardProgram>) {
        val current = all()
        val added = programs.filter { incoming -> current.none { it.key == incoming.key } }.distinctBy { it.key }
        if (added.isNotEmpty()) prefs.edit().putString(KEY, json.encodeToString(current + added)).apply()
    }

    private companion object {
        const val KEY = "programs"
        const val KEY_UPGRADE = "rules_upgrade"
    }
}

/** One statement's reported rewards against the estimate for the same cycle. */
data class StatementCheck(
    val statementDate: java.time.LocalDate,
    /** What the statement says, in the card's currency; null when it gave no total or points can't be valued. */
    val reported: BigDecimal?,
    val reportedPoints: BigDecimal?,
    val estimate: BigDecimal,
    /** The statement lines that mention rewards, to see what it does say. */
    val lines: List<String>,
    /** Statement rows in this cycle that the app has no matching transaction for. */
    val missingRows: List<com.moneytracker.app.data.statement.RowRewardEntry> = emptyList(),
    /** The cycle the figure belongs to, inclusive. */
    val from: java.time.LocalDate = statementDate,
    val to: java.time.LocalDate = statementDate,
    /** The purchases and refunds that went into [estimate]. */
    val counted: List<TransactionReward> = emptyList(),
    /** The statement credits its cashback a cycle after it was earned. */
    val forPreviousCycle: Boolean = false
) {
    val difference: BigDecimal? get() = reported?.let { estimate - it }
    /** Worth a look: off by more than Rs 10 and 5%. */
    val mismatch: Boolean
        get() = difference?.let { d -> d.abs() > BigDecimal(10) && d.abs() > (reported ?: BigDecimal.ZERO).abs().multiply(BigDecimal("0.05")) } ?: false
}

object StatementChecker {
    /**
     * Compares each statement's reward total with what [rewards] estimate for the cycle ending on its date
     * (the month up to the statement date). Caps are applied per calendar month in the estimate, so a card
     * whose cap follows the statement cycle can differ a little at the edges.
     */
    fun check(
        program: CardRewardProgram,
        rewards: List<TransactionReward>,
        entries: List<com.moneytracker.app.data.statement.StatementRewardEntry>,
        unmatchedRows: List<com.moneytracker.app.data.statement.RowRewardEntry> = emptyList(),
        /** Only rewards in this currency (the card's own) are compared with the statement, never added to another's. */
        currency: String = "INR"
    ): List<StatementCheck> = entries.sortedByDescending { it.statementDay }.map { e ->
        val end = if (e.forPreviousCycle) e.statementDate.minusMonths(1) else e.statementDate
        val start = end.minusMonths(1).plusDays(1)
        // A purchase a statement printed belongs to that statement's cycle, even when it posted a day after the cycle closed.
        // SmartBuy cashback is rupees, not the points the statement totals; a partner shop's CashPoints are in them.
        val inCycle = rewards.filter { !it.cashbackCredit && it.transaction.currency == currency }.filter {
            val d = it.transaction.dateTime.toLocalDate()
            it.statementDay?.let { s -> s == e.statementDay } ?: (!d.isBefore(start) && !d.isAfter(end))
        }
        val estimate = inCycle.fold(BigDecimal.ZERO) { acc, r -> acc + r.reward - r.cashbackPart }
        val earned = e.earned?.toBigDecimalOrNull()
        val value = program.pointValue?.toBigDecimalOrNull()
        StatementCheck(
            statementDate = e.statementDate,
            reported = when {
                earned == null -> null
                !e.isPoints -> earned
                value != null -> earned.multiply(value).setScale(2, RoundingMode.HALF_UP)
                else -> null
            },
            reportedPoints = earned?.takeIf { e.isPoints },
            estimate = estimate,
            lines = e.lines,
            from = start, to = end, counted = inCycle, forPreviousCycle = e.forPreviousCycle,
            missingRows = unmatchedRows.filter { it.day >= start.toEpochDay() && it.day <= end.toEpochDay() }.sortedBy { it.day }
        )
    }
}

/**
 * Rates the person set on single purchases (e.g. an Amazon gift card that earns 2% when the card's
 * Amazon rate is 5%), by transaction id. Kept in plain preferences.
 */
@Singleton
class RewardOverrideStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("reward_overrides", Context.MODE_PRIVATE)
    private val _rates = kotlinx.coroutines.flow.MutableStateFlow(load())
    val rates: kotlinx.coroutines.flow.StateFlow<Map<Long, String>> = _rates

    /** Sets the rate for [transactionId]; null puts it back to the card's own rate. */
    @Synchronized
    fun set(transactionId: Long, ratePercent: String?) {
        val next = if (ratePercent == null) _rates.value - transactionId else _rates.value + (transactionId to ratePercent)
        prefs.edit().putString(KEY, next.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()
        _rates.value = next
    }

    /** Adds rates from a backup (already mapped to this phone's transaction ids); one set here stays. */
    @Synchronized
    fun restore(incoming: Map<Long, String>) {
        val added = incoming.filterKeys { it !in _rates.value }
        if (added.isEmpty()) return
        val next = _rates.value + added
        prefs.edit().putString(KEY, next.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()
        _rates.value = next
    }

    private fun load(): Map<Long, String> =
        prefs.getString(KEY, null).orEmpty().split(';').mapNotNull { p ->
            val (id, rate) = p.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            id.toLongOrNull()?.let { it to rate }
        }.toMap()

    private companion object { const val KEY = "rates" }
}
/**
 * The reward a statement printed on each transaction row (ICICI does), to use as the real reward of the matching
 * purchase. The match is made once for the whole card by [StatementReconciler]; rows no transaction matched are
 * [unmatched].
 */
class ReportedRewards private constructor(
    private val byTransaction: Map<Long, com.moneytracker.app.data.statement.RowRewardEntry>,
    private val unmatchedRows: List<com.moneytracker.app.data.statement.RowRewardEntry>,
    private val value: BigDecimal
) {
    /** What the statement says this transaction earned (in the card's currency), or null if no statement row matches. */
    fun take(tx: TransactionEntity): BigDecimal? = takeRow(tx)?.let { reward(it) }

    /** The statement row matching this transaction, or null. */
    fun takeRow(tx: TransactionEntity): com.moneytracker.app.data.statement.RowRewardEntry? = byTransaction[tx.id]

    fun reward(row: com.moneytracker.app.data.statement.RowRewardEntry): BigDecimal? =
        row.points.toBigDecimalOrNull()?.multiply(value)?.setScale(2, RoundingMode.HALF_UP)

    /** The statement rows no transaction matched. */
    fun unmatched(): List<com.moneytracker.app.data.statement.RowRewardEntry> = unmatchedRows

    companion object {
        /**
         * Null unless [program] values a point and [entries] hold printed rows. [transactions] may hold other cards';
         * only [program]'s card is matched.
         */
        fun of(
            program: CardRewardProgram,
            entries: List<com.moneytracker.app.data.statement.StatementRewardEntry>,
            transactions: List<TransactionEntity>
        ): ReportedRewards? {
            val value = program.pointValue?.toBigDecimalOrNull() ?: return null
            if (entries.none { it.rows.isNotEmpty() }) return null
            val mine = transactions.filter { it.accountNumber == program.cardLast4 && it.bankName.orEmpty().trim().equals(program.bankName.trim(), ignoreCase = true) }
            val result = StatementReconciler.reconcile(mine, entries)
            return ReportedRewards(result.rowFor, result.unmatchedRows, value)
        }
    }
}

/** Rewards a statement totals beyond what its purchase rows add up to, so they belong to no one purchase. */
data class StatementBonus(val date: java.time.LocalDate, val amount: BigDecimal) {
    companion object {
        /**
         * Only Tata Neu: its statement's total is its rows plus the NeuCoins listed apart (a partner's bonus). Other
         * cards' totals have not been checked against their rows this way.
         */
        fun of(program: CardRewardProgram, entries: List<com.moneytracker.app.data.statement.StatementRewardEntry>): List<StatementBonus> {
            if ("tata neu" !in program.name.lowercase()) return emptyList()
            val value = program.pointValue?.toBigDecimalOrNull() ?: return emptyList()
            return entries.filter { it.isPoints && it.rows.isNotEmpty() }.mapNotNull { entry ->
                val earned = entry.earned?.replace(",", "")?.toBigDecimalOrNull() ?: return@mapNotNull null
                val inRows = entry.rows.fold(BigDecimal.ZERO) { sum, row ->
                    val points = row.points.toBigDecimalOrNull() ?: BigDecimal.ZERO
                    if (row.credit) sum - points else sum + points
                }
                val extra = (earned - inRows).multiply(value)
                if (extra.signum() > 0) StatementBonus(entry.statementDate, extra) else null
            }
        }
    }
}
