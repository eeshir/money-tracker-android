package com.moneytracker.app.presentation.common

import com.moneytracker.app.data.database.entity.BudgetImpactType
import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * The Income / Expenses / Net of the Transactions screen. The case that started this: card purchases made last month are
 * paid for by a bill paid this month. The bill payment is kept out of spending (it pairs with the card purchases it
 * settles), so the purchases themselves must count in the month they were made, not disappear from Net.
 */
class ComputeCurrencyTotalsTest {
    private fun bd(s: String) = BigDecimal(s)
    private var next = 0L

    private fun row(
        type: TransactionType, amount: String, month: Int = 10, currency: String = "INR", excluded: Boolean = false,
        impact: BudgetImpactType? = null, category: String? = null, lentShares: String? = null, refundedTotal: String? = null
    ) = TransactionEntity(
        id = ++next, amount = bd(amount), merchantName = "M$next", category = "Others", transactionType = type,
        dateTime = LocalDateTime.of(2026, month, 10, 12, 0), transactionHash = "h$next", currency = currency,
        excludedFromAnalytics = excluded, budgetImpactType = impact, budgetCategory = category,
        lentSharesTotal = lentShares?.let(::bd), refundedTotal = refundedTotal?.let(::bd)
    )

    private fun totals(rows: List<TransactionEntity>, creditAsExpense: Boolean, currency: String = "INR") =
        computeCurrencyTotals(rows, creditAsExpense).getValue(currency)

    private fun same(expected: String, actual: BigDecimal) =
        assertTrue("expected $expected but was $actual", actual.compareTo(bd(expected)) == 0)

    // October: salary, rent from the bank, card purchases. November: the card bill is paid (excluded from spending).
    private val october = listOf(
        row(TransactionType.INCOME, "50000", month = 10),
        row(TransactionType.EXPENSE, "20000", month = 10),
        row(TransactionType.CREDIT, "6000", month = 10),
        row(TransactionType.CREDIT, "4000", month = 10)
    )
    private val november = listOf(
        row(TransactionType.INCOME, "50000", month = 11),
        row(TransactionType.EXPENSE, "10000", month = 11, excluded = true) // the card bill payment
    )

    @Test
    fun `card spend counts in the month it was made, so last month's net is not inflated`() {
        val t = totals(october, creditAsExpense = true)
        same("30000", t.expenses)
        same("20000", t.netBalance)
        assertTrue(t.creditInExpenses)
    }

    @Test
    fun `paying the bill next month does not count the same money again`() {
        val t = totals(november, creditAsExpense = true)
        same("0", t.expenses)
        same("50000", t.netBalance)
    }

    @Test
    fun `the two months together count the card spend exactly once`() {
        val both = totals(october + november, creditAsExpense = true)
        same("30000", both.expenses)
    }

    @Test
    fun `with the setting off card spend is a separate channel and net ignores it`() {
        val t = totals(october, creditAsExpense = false)
        same("20000", t.expenses)
        same("30000", t.netBalance)
        same("10000", t.credit)
        assertFalse(t.creditInExpenses)
    }

    @Test
    fun `the card figure is available either way`() {
        same("10000", totals(october, true).credit)
        same("10000", totals(october, false).credit)
    }

    @Test
    fun `a refund shrinks expenses including card spend, and is not income`() {
        val rows = october + row(TransactionType.INCOME, "2500", month = 10, impact = BudgetImpactType.DEDUCT_SPENT, category = "Shopping")
        val t = totals(rows, true)
        same("27500", t.expenses)
        same("50000", t.income)
    }

    @Test
    fun `a refund bigger than the spend cannot make expenses negative`() {
        val rows = listOf(
            row(TransactionType.CREDIT, "100"),
            row(TransactionType.INCOME, "900", impact = BudgetImpactType.DEDUCT_SPENT, category = "Shopping")
        )
        same("0", totals(rows, true).expenses)
    }

    @Test
    fun `a refund linked to an expense counts through the expense, not as a second deduction`() {
        // 30,000 rent already shows 18,000 paid back; the 18,000 refund row itself (no category) counts for nothing.
        val rows = listOf(
            row(TransactionType.EXPENSE, "30000", refundedTotal = "18000"),
            row(TransactionType.INCOME, "18000", impact = BudgetImpactType.DEDUCT_SPENT)
        )
        val t = totals(rows, true)
        same("12000", t.expenses)
        same("0", t.income)
    }

    @Test
    fun `a card purchase paid back by a linked refund counts its net cost`() {
        val rows = listOf(row(TransactionType.CREDIT, "5000", refundedTotal = "1200"))
        same("3800", totals(rows, true).expenses)
        same("3800", totals(rows, true).credit)
    }

    @Test
    fun `the shared part of a card bill is not your spend`() {
        val rows = listOf(row(TransactionType.CREDIT, "1000", lentShares = "400"))
        same("600", totals(rows, true).expenses)
    }

    @Test
    fun `currencies are never added together`() {
        val rows = listOf(
            row(TransactionType.EXPENSE, "500", currency = "INR"), row(TransactionType.CREDIT, "300", currency = "INR"),
            row(TransactionType.EXPENSE, "10", currency = "USD"), row(TransactionType.CREDIT, "5", currency = "USD")
        )
        val all = computeCurrencyTotals(rows, true)
        same("800", all.getValue("INR").expenses)
        same("15", all.getValue("USD").expenses)
    }

    @Test
    fun `transfers and investments stay out of expenses and net`() {
        val rows = listOf(
            row(TransactionType.INCOME, "1000"), row(TransactionType.EXPENSE, "100"),
            row(TransactionType.TRANSFER, "400"), row(TransactionType.INVESTMENT, "300")
        )
        val t = totals(rows, true)
        same("100", t.expenses)
        same("900", t.netBalance)
        same("400", t.transfer)
        same("300", t.investment)
    }

    @Test
    fun `sums are exact to the paisa`() {
        val rows = listOf(row(TransactionType.EXPENSE, "0.10"), row(TransactionType.EXPENSE, "0.20"), row(TransactionType.CREDIT, "0.30"))
        assertEquals(bd("0.60"), totals(rows, true).expenses)
    }

    @Test
    fun `an excluded card purchase is left out like any other excluded row`() {
        val rows = listOf(row(TransactionType.CREDIT, "700", excluded = true), row(TransactionType.CREDIT, "300"))
        same("300", totals(rows, true).expenses)
    }

    @Test
    fun `nothing at all gives no currency rather than zeros of the wrong one`() {
        assertTrue(computeCurrencyTotals(emptyList(), true).isEmpty())
    }
}
