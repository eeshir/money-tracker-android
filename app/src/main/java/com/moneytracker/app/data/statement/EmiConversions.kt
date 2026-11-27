package com.moneytracker.app.data.statement

import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import java.time.temporal.ChronoUnit

/**
 * A card purchase turned into instalments. HDFC prints a credit that cancels the purchase ("AGGREGATOR EMI -
 * OFFUS CREDIT") and then bills principal, interest and tax each month. Spending is counted as it is billed: the
 * monthly rows count, while the purchase and the credit that cancels it are left out of totals.
 *
 * A refund on such a purchase is put straight against the loan: the statement credits it and charges the same
 * amount back as "EMI REFUND PRE PAYMENT". No money reaches the person either way, so both are left out too.
 */
object EmiConversions {
    private const val CONVERSION_DAYS = 30L
    private const val PREPAYMENT_DAYS = 7L

    private fun isConversionCredit(t: TransactionEntity): Boolean {
        if (t.transactionType != TransactionType.INCOME) return false
        val text = t.merchantName.lowercase()
        return "aggregator emi" in text && "credit" in text
    }

    private fun isLoanPrepayment(t: TransactionEntity) =
        t.transactionType == TransactionType.CREDIT && "emi refund pre payment" in t.merchantName.lowercase()

    private fun TransactionEntity.sameCardAndAmount(other: TransactionEntity) =
        accountNumber != null && accountNumber == other.accountNumber && bankName == other.bankName &&
            currency == other.currency && amount.compareTo(other.amount) == 0

    private fun daysFrom(a: TransactionEntity, b: TransactionEntity) =
        ChronoUnit.DAYS.between(a.dateTime.toLocalDate(), b.dateTime.toLocalDate())

    /** Prepayment debit → the refund it used up. One refund per debit, the closest in date. */
    private fun loanRefunds(live: List<TransactionEntity>): Map<TransactionEntity, TransactionEntity> {
        val taken = HashSet<Long>()
        return live.filter(::isLoanPrepayment).sortedBy { it.dateTime }.mapNotNull { debit ->
            val refund = live.filter {
                it.id !in taken && it.transactionType == TransactionType.INCOME && it.sameCardAndAmount(debit) &&
                    !CardPaymentPairs.isCardPayment(it.copy(excludedFromAnalytics = false)) &&
                    !it.merchantName.contains("cashback", ignoreCase = true) &&
                    !it.merchantName.contains("smartbuy", ignoreCase = true) &&
                    Math.abs(daysFrom(it, debit)) <= PREPAYMENT_DAYS
            }.minByOrNull { Math.abs(daysFrom(it, debit)) } ?: return@mapNotNull null
            taken += refund.id
            debit to refund
        }.toMap()
    }

    /** Refunds that paid down an EMI loan: they take back no reward and are not money received. */
    fun refundsPaidIntoLoan(transactions: List<TransactionEntity>): Set<Long> =
        loanRefunds(transactions.filter { !it.isDeleted }).values.mapTo(HashSet()) { it.id }

    /**
     * The rows to leave out of totals now. It acts only when a conversion credit or a prepayment debit is still
     * counted, so a row the person later puts back into totals stays there.
     */
    fun toExclude(transactions: List<TransactionEntity>): List<TransactionEntity> {
        val live = transactions.filter { !it.isDeleted }
        val result = LinkedHashMap<Long, TransactionEntity>()
        val used = HashSet<Long>()
        for (credit in live.filter { isConversionCredit(it) && !it.excludedFromAnalytics }.sortedBy { it.dateTime }) {
            // Without its purchase the credit is left alone until the purchase is in the app.
            val purchase = live.filter {
                it.id !in used && it.transactionType == TransactionType.CREDIT && !it.excludedFromAnalytics &&
                    it.sameCardAndAmount(credit) && daysFrom(it, credit) in 0..CONVERSION_DAYS
            }.maxByOrNull { it.dateTime } ?: continue
            used += purchase.id
            result[purchase.id] = purchase
            result[credit.id] = credit
        }
        for ((debit, refund) in loanRefunds(live)) {
            if (debit.excludedFromAnalytics) continue
            result[debit.id] = debit
            if (!refund.excludedFromAnalytics) result[refund.id] = refund
        }
        return result.values.toList()
    }
}
