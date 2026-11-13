package com.moneytracker.app.presentation.common

import java.math.BigDecimal
import com.moneytracker.app.utils.forTotals

/**
 * Data class to hold financial totals grouped by currency
 */
data class CurrencyGroupedTotals(
    val totalsByCurrency: Map<String, CurrencyTotals> = emptyMap(),
    val availableCurrencies: List<String> = emptyList(),
    val transactionCount: Int = 0
) {
    fun getTotalsForCurrency(currency: String): CurrencyTotals {
        return totalsByCurrency[currency] ?: CurrencyTotals(currency = currency)
    }

    fun hasAnyCurrency(): Boolean = availableCurrencies.isNotEmpty()

    fun getPrimaryCurrency(preferredCurrency: String = "INR"): String {
        return when {
            availableCurrencies.contains(preferredCurrency) -> preferredCurrency
            availableCurrencies.isNotEmpty() -> availableCurrencies.first()
            else -> preferredCurrency
        }
    }
}

/**
 * Financial totals for a specific currency
 */
data class CurrencyTotals(
    val currency: String,
    val income: BigDecimal = BigDecimal.ZERO,
    val expenses: BigDecimal = BigDecimal.ZERO,
    val credit: BigDecimal = BigDecimal.ZERO,
    val transfer: BigDecimal = BigDecimal.ZERO,
    val investment: BigDecimal = BigDecimal.ZERO,
    val transactionCount: Int = 0,
    /** True when [expenses] already include the card spend in [credit] (the "count credit card as expense" setting). */
    val creditInExpenses: Boolean = false
) {
    /**
     * Net = income − expenses. With "count credit card as expense" on (the default) [expenses]
     * includes card purchases in the month they were made, so a month's card spend is never lost just because
     * the card bill is paid in the next month (the bill payment itself is kept out of spending, so it is not
     * counted twice). With it off, card spend is a separate channel and is not deducted. Transfers and
     * investments are always separate channels (see the home Cash-Flow card).
     */
    val netBalance: BigDecimal
        get() = income - expenses
}
/**
 * Income, expenses and the other channels of [transactions], per currency, counted the way Home and Analytics count them:
 * rows go through [forTotals] first (analytics-excluded rows such as a card bill payment, and the loan or shared part of a
 * row, are left out); a refund (income marked Refund) shrinks expenses, floored at zero, and is not income; and with
 * [countCreditAsExpense] card purchases are part of expenses. Currencies are never added together.
 */
fun computeCurrencyTotals(
    transactions: List<com.moneytracker.app.data.database.entity.TransactionEntity>,
    countCreditAsExpense: Boolean
): Map<String, CurrencyTotals> {
    val counted = transactions.forTotals()
    return counted.groupBy { it.currency }.mapValues { (currency, rows) ->
        fun sum(filter: (com.moneytracker.app.data.database.entity.TransactionEntity) -> Boolean) =
            rows.filter(filter).fold(BigDecimal.ZERO) { acc, t -> acc + t.amount }
        val isRefund = { t: com.moneytracker.app.data.database.entity.TransactionEntity ->
            t.transactionType == com.moneytracker.app.data.database.entity.TransactionType.INCOME &&
                t.budgetImpactType == com.moneytracker.app.data.database.entity.BudgetImpactType.DEDUCT_SPENT
        }
        val refundTotal = sum(isRefund)
        val income = sum { it.transactionType == com.moneytracker.app.data.database.entity.TransactionType.INCOME && !isRefund(it) }
        val bank = sum { it.transactionType == com.moneytracker.app.data.database.entity.TransactionType.EXPENSE }
        val credit = sum { it.transactionType == com.moneytracker.app.data.database.entity.TransactionType.CREDIT }
        val rawExpenses = if (countCreditAsExpense) bank + credit else bank
        CurrencyTotals(
            currency = currency,
            income = income,
            expenses = (rawExpenses - refundTotal).coerceAtLeast(BigDecimal.ZERO),
            credit = credit,
            transfer = sum { it.transactionType == com.moneytracker.app.data.database.entity.TransactionType.TRANSFER },
            investment = sum { it.transactionType == com.moneytracker.app.data.database.entity.TransactionType.INVESTMENT },
            transactionCount = rows.size,
            creditInExpenses = countCreditAsExpense
        )
    }
}
