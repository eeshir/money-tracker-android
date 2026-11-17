package com.moneytracker.app.data.refunds

import com.moneytracker.app.data.database.entity.BudgetImpactType
import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import com.moneytracker.app.data.mapper.isCreditCardPayment
import java.math.BigDecimal
import java.time.Duration

/**
 * Finds the card purchases a merchant refund on a credit card pays back. Pure, no database.
 *
 * A card statement (or SMS) lists a refund as money coming in; on its own it is plain income and
 * lowers no spending. Only a refund linked to the purchase it reverses nets that purchase's month
 * (see docs/REFUND_LINKS.md), so this proposes the links.
 */
object CardRefundMatcher {
    /** How far before a refund its purchase can be. */
    private val LOOKBACK: Duration = Duration.ofDays(365)

    /** A refund can post a day before the purchase's own row is stamped (statement rows are date-only). */
    private val SLACK: Duration = Duration.ofDays(1)

    private val CASHBACK_WORDS = listOf("cashback", "reward", "payment", "bonus", "smartbuy", "emi", "aggregator", "interest", "fee")

    /** Whether [t] is a merchant refund still waiting to be linked: not a bill payment, cashback or reward. */
    fun isUnlinkedMerchantRefund(t: TransactionEntity): Boolean {
        if (t.isDeleted || t.transactionType != TransactionType.INCOME || t.excludedFromAnalytics) return false
        if (t.budgetImpactType != null || t.accountNumber.isNullOrBlank() || t.bankName.isNullOrBlank()) return false
        if (isCreditCardPayment(t.merchantName, t.smsBody, TransactionType.INCOME)) return false
        val text = "${t.merchantName} ${t.description.orEmpty()}".lowercase()
        return CASHBACK_WORDS.none { text.contains(it) } && key(t.merchantName).length >= MIN_KEY
    }

    /**
     * The links to make for [refund]: purchases on the same card from the same merchant, an exact-amount
     * one first, then the latest. [roomUsed] carries what earlier refunds of the same run already took.
     * Empty when nothing fits.
     */
    fun plan(
        refund: TransactionEntity,
        purchases: List<TransactionEntity>,
        roomUsed: MutableMap<Long, BigDecimal> = HashMap()
    ): List<RefundLinks.Request> {
        val merchant = key(refund.merchantName)
        fun room(p: TransactionEntity) =
            p.amount - (p.refundedTotal ?: BigDecimal.ZERO) - (roomUsed[p.id] ?: BigDecimal.ZERO)

        val candidates = purchases.filter {
            it.transactionType == TransactionType.CREDIT &&
                it.bankName == refund.bankName && it.accountNumber == refund.accountNumber &&
                RefundLinks.canLink(refund.copy(budgetImpactType = BudgetImpactType.DEDUCT_SPENT), it) &&
                sameMerchant(merchant, key(it.merchantName)) &&
                !it.dateTime.isAfter(refund.dateTime.plus(SLACK)) &&
                !it.dateTime.isBefore(refund.dateTime.minus(LOOKBACK)) &&
                room(it) > BigDecimal.ZERO
        }
        val ordered = candidates.sortedWith(
            compareByDescending<TransactionEntity> { room(it).compareTo(refund.amount) >= 0 && it.amount.compareTo(refund.amount) == 0 }
                .thenByDescending { it.dateTime }
        )
        var left = refund.amount
        val requests = ArrayList<RefundLinks.Request>()
        for (p in ordered) {
            if (left <= BigDecimal.ZERO) break
            val take = room(p).min(left)
            if (take <= BigDecimal.ZERO) continue
            requests += RefundLinks.Request(p.id, null, take)
            roomUsed[p.id] = (roomUsed[p.id] ?: BigDecimal.ZERO) + take
            left -= take
        }
        return requests
    }

    private const val MIN_KEY = 4

    private fun key(name: String?) = name.orEmpty().lowercase().filter { it.isLetterOrDigit() }

    private fun sameMerchant(a: String, b: String) =
        a.length >= MIN_KEY && b.length >= MIN_KEY && (a == b || a.contains(b) || b.contains(a))
}
