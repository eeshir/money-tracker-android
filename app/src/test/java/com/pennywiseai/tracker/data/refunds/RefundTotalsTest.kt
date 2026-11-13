package com.pennywiseai.tracker.data.refunds

import com.pennywiseai.tracker.data.database.entity.BudgetImpactType
import com.pennywiseai.tracker.data.database.entity.RefundAllocationEntity
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionSplitEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.data.database.entity.TransactionWithSplits
import com.pennywiseai.tracker.data.repository.aggregateBudgetCategorySpending
import com.pennywiseai.tracker.presentation.common.TransactionTypeFilter
import com.pennywiseai.tracker.ui.screens.analytics.analyticsRowsFor
import com.pennywiseai.tracker.ui.screens.analytics.netOf
import com.pennywiseai.tracker.ui.screens.analytics.refundNetting
import com.pennywiseai.tracker.utils.countedAmount
import com.pennywiseai.tracker.utils.forTotals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Shared-expense scenarios end to end: someone pays a bill and others pay part of it back. The refunds are
 * linked to the expenses with the real rules ([RefundLinks.plan] / [RefundLinks.stored]) and the figures are
 * read the way the screens read them (forTotals, Analytics rows, Budgets aggregation).
 */
class RefundTotalsTest {
    private fun bd(s: String) = BigDecimal(s)

    /** A little world: rows and the links between them, with the stored copies derived the way the repository does. */
    private class World {
        val expenses = linkedMapOf<Long, TransactionEntity>()
        val splits = linkedMapOf<Long, MutableList<TransactionSplitEntity>>()
        val refunds = linkedMapOf<Long, TransactionEntity>()
        val allocations = mutableListOf<RefundAllocationEntity>()
        private var nextSplitId = 1L

        fun expense(id: Long, amount: String, category: String = "Rent", month: Int = 10, parts: Map<String, String> = emptyMap(), currency: String = "INR") {
            expenses[id] = TransactionEntity(
                id = id, amount = BigDecimal(amount), merchantName = "Landlord$id", category = category,
                transactionType = TransactionType.EXPENSE, dateTime = LocalDateTime.of(2026, month, 3, 12, 0),
                transactionHash = "e$id", bankName = "Bank", accountNumber = "1111", currency = currency
            )
            splits[id] = parts.map { (c, a) ->
                TransactionSplitEntity(id = nextSplitId++, transactionId = id, category = c, amount = BigDecimal(a))
            }.toMutableList()
        }

        fun refund(id: Long, amount: String, month: Int = 10, category: String? = null, currency: String = "INR") {
            refunds[id] = TransactionEntity(
                id = id, amount = BigDecimal(amount), merchantName = "Friend$id", category = "Others",
                transactionType = TransactionType.INCOME, dateTime = LocalDateTime.of(2026, month, 5, 12, 0),
                transactionHash = "r$id", bankName = "Bank", accountNumber = "1111", currency = currency,
                budgetImpactType = BudgetImpactType.DEDUCT_SPENT, budgetCategory = category
            )
        }

        fun link(refundId: Long, vararg requests: RefundLinks.Request): RefundLinks.Outcome {
            val refund = refunds.getValue(refundId)
            val ids = (requests.map { it.expenseId } + allocations.filter { it.refundId == refundId }.map { it.expenseId }).distinct()
            val targets = ids.mapNotNull { id ->
                val e = expenses[id] ?: return@mapNotNull null
                id to RefundLinks.Target(e, splits[id].orEmpty(), allocations.filter { it.expenseId == id && it.refundId != refundId })
            }.toMap()
            val outcome = RefundLinks.plan(refund, requests.toList(), targets)
            allocations.removeAll { it.refundId == refundId }
            allocations += outcome.accepted.map { RefundAllocationEntity(refundId = refundId, expenseId = it.expenseId, category = it.category, amount = it.amount) }
            refreshStored(ids)
            return outcome
        }

        /** Same as RefundLinkRepository.refresh: live allocations only (a refund with a category or deleted counts nothing). */
        fun refreshStored(ids: Collection<Long>) {
            for (id in ids) {
                val e = expenses[id] ?: continue
                val live = allocations.filter { a ->
                    a.expenseId == id && refunds[a.refundId]?.let { RefundLinks.isRefund(it) && it.budgetCategory == null } == true
                }
                val stored = RefundLinks.stored(e, splits[id].orEmpty(), live)
                expenses[id] = e.copy(refundedTotal = stored.total)
                splits[id] = splits[id].orEmpty().map { it.copy(refundedAmount = stored.bySplitId[it.id]) }.toMutableList()
            }
        }

        fun deleteRefund(id: Long) {
            refunds[id] = refunds.getValue(id).copy(isDeleted = true)
            refreshStored(allocations.filter { it.refundId == id }.map { it.expenseId })
        }

        fun rows(): List<TransactionWithSplits> =
            expenses.values.filter { !it.isDeleted }.map { TransactionWithSplits(it, splits[it.id].orEmpty()) } +
                refunds.values.filter { !it.isDeleted }.map { TransactionWithSplits(it, emptyList()) }
    }

    private fun assertMoney(expected: String, actual: BigDecimal?) =
        assertTrue("expected $expected but was $actual", actual != null && actual.compareTo(BigDecimal(expected)) == 0)

    private fun req(expense: Long, amount: String, category: String? = null) = RefundLinks.Request(expense, category, bd(amount))

    /** Total spend as Analytics' Expense view computes it. */
    private fun analyticsTotal(rows: List<TransactionWithSplits>): BigDecimal = runBlocking {
        val spend = analyticsRowsFor(rows, TransactionTypeFilter.EXPENSE).fold(BigDecimal.ZERO) { a, r -> a + r.transaction.amount }
        netOf(spend, refundNetting(rows, TransactionTypeFilter.EXPENSE, null, emptyMap()) { it.amount }.total)
    }

    private fun incomeTotal(rows: List<TransactionWithSplits>): BigDecimal =
        analyticsRowsFor(rows, TransactionTypeFilter.INCOME).fold(BigDecimal.ZERO) { a, r -> a + r.transaction.amount }

    private fun budgetCategories(rows: List<TransactionWithSplits>) = runBlocking {
        aggregateBudgetCategorySpending(rows, { _, a -> a }, { it.amount }).categoryAmounts
    }

    // ---- the two scenarios that started this ----------------------------------------------------

    @Test
    fun `30k rent with 20k paid back leaves 10k, and the 20k is not income`() {
        val w = World()
        w.expense(1, "30000"); w.refund(2, "20000")
        w.link(2, req(1, "20000"))
        assertMoney("10000", analyticsTotal(w.rows()))
        assertMoney("10000", budgetCategories(w.rows())["Rent"])
        assertEquals(0, incomeTotal(w.rows()).signum())
    }

    @Test
    fun `30k rent, 18k paid back, 2k of it was food owed to a flatmate`() {
        val w = World()
        w.expense(1, "30000", parts = mapOf("Rent" to "28000", "Food" to "2000"))
        w.refund(2, "18000")
        w.link(2, req(1, "18000", "Rent"))
        val cats = budgetCategories(w.rows())
        assertMoney("10000", cats["Rent"])
        assertMoney("2000", cats["Food"])
        assertMoney("12000", analyticsTotal(w.rows()))
    }

    @Test
    fun `the same case when the 18k comes as 10k and 8k from two flatmates`() {
        val w = World()
        w.expense(1, "30000", parts = mapOf("Rent" to "28000", "Food" to "2000"))
        w.refund(2, "10000"); w.refund(3, "8000")
        w.link(2, req(1, "10000", "Rent")); w.link(3, req(1, "8000", "Rent"))
        val cats = budgetCategories(w.rows())
        assertMoney("10000", cats["Rent"])
        assertMoney("2000", cats["Food"])
    }

    // ---- floater --------------------------------------------------------------------------------

    @Test
    fun `an unlinked refund counts for nothing, neither spend nor income`() {
        val w = World()
        w.expense(1, "30000"); w.refund(2, "20000")
        assertMoney("30000", analyticsTotal(w.rows()))
        assertMoney("30000", budgetCategories(w.rows())["Rent"])
        assertEquals(0, incomeTotal(w.rows()).signum())
    }

    @Test
    fun `the unlinked rest of a part-linked refund also counts for nothing`() {
        val w = World()
        w.expense(1, "30000"); w.refund(2, "20000")
        w.link(2, req(1, "8000"))
        assertMoney("22000", analyticsTotal(w.rows()))
        assertEquals(0, incomeTotal(w.rows()).signum())
    }

    @Test
    fun `one refund shared over two expenses in different categories`() {
        val w = World()
        w.expense(1, "30000", category = "Rent"); w.expense(2, "5000", category = "Groceries"); w.refund(3, "18000")
        w.link(3, req(1, "15000"), req(2, "3000"))
        val cats = budgetCategories(w.rows())
        assertMoney("15000", cats["Rent"])
        assertMoney("2000", cats["Groceries"])
        assertMoney("17000", analyticsTotal(w.rows()))
    }

    // ---- time -----------------------------------------------------------------------------------

    @Test
    fun `a repayment in the next month corrects the month that paid`() {
        val w = World()
        w.expense(1, "30000", month = 10); w.refund(2, "20000", month = 11)
        w.link(2, req(1, "20000"))
        val october = w.rows().filter { it.transaction.dateTime.monthValue == 10 }
        val november = w.rows().filter { it.transaction.dateTime.monthValue == 11 }
        assertMoney("10000", analyticsTotal(october))
        assertMoney("10000", budgetCategories(october)["Rent"])
        // November is untouched: the refund neither lowers its spend nor shows as income.
        assertEquals(0, analyticsTotal(november).signum())
        assertEquals(0, incomeTotal(november).signum())
    }

    @Test
    fun `a refund before the expense it pays back still lands on that expense`() {
        val w = World()
        w.refund(2, "500", month = 9); w.expense(1, "3000", month = 10)
        w.link(2, req(1, "500"))
        assertEquals(bd("2500"), analyticsTotal(w.rows().filter { it.transaction.dateTime.monthValue == 10 }))
    }

    // ---- limits ---------------------------------------------------------------------------------

    @Test
    fun `two refunds cannot together pay back more than the expense cost`() {
        val w = World()
        w.expense(1, "1000"); w.refund(2, "800"); w.refund(3, "800")
        w.link(2, req(1, "800")); val second = w.link(3, req(1, "800"))
        assertEquals(bd("200"), second.total)
        assertEquals(bd("0"), analyticsTotal(w.rows()).setScale(0))
    }

    @Test
    fun `a fully paid back expense counts nothing and disappears from the rows`() {
        val w = World()
        w.expense(1, "1000"); w.refund(2, "1000")
        w.link(2, req(1, "1000"))
        assertNull(w.rows().first { it.transaction.id == 1L }.forTotals())
        assertTrue(analyticsRowsFor(w.rows(), TransactionTypeFilter.EXPENSE).isEmpty())
    }

    @Test
    fun `re-linking replaces the old links instead of adding to them`() {
        val w = World()
        w.expense(1, "1000"); w.expense(2, "1000"); w.refund(3, "600")
        w.link(3, req(1, "600"))
        w.link(3, req(2, "600"))
        assertEquals(bd("1000"), w.expenses.getValue(1).amount - (w.expenses.getValue(1).refundedTotal ?: BigDecimal.ZERO))
        assertEquals(bd("400"), w.expenses.getValue(2).amount - (w.expenses.getValue(2).refundedTotal ?: BigDecimal.ZERO))
    }

    @Test
    fun `unlinking puts the money back`() {
        val w = World()
        w.expense(1, "1000"); w.refund(2, "400")
        w.link(2, req(1, "400"))
        w.link(2)
        assertMoney("1000", analyticsTotal(w.rows()))
    }

    @Test
    fun `deleting the refund restores the expense`() {
        val w = World()
        w.expense(1, "1000"); w.refund(2, "400")
        w.link(2, req(1, "400"))
        assertMoney("600", analyticsTotal(w.rows()))
        w.deleteRefund(2)
        assertMoney("1000", analyticsTotal(w.rows()))
    }

    @Test
    fun `a refund given a category afterwards counts once, through the category, never twice`() {
        val w = World()
        w.expense(1, "1000"); w.refund(2, "400")
        w.link(2, req(1, "400"))
        w.refunds[2] = w.refunds.getValue(2).copy(budgetCategory = "Rent")
        w.refreshStored(listOf(1))
        // links go dormant, the category refund takes over: 1000 - 400, not 1000 - 400 - 400
        assertMoney("600", analyticsTotal(w.rows()))
        assertMoney("600", budgetCategories(w.rows())["Rent"])
    }

    @Test
    fun `an expense edited down below its links never goes negative`() {
        val w = World()
        w.expense(1, "1000"); w.refund(2, "900")
        w.link(2, req(1, "900"))
        w.expenses[1] = w.expenses.getValue(1).copy(amount = bd("500"))
        w.refreshStored(listOf(1))
        assertEquals(0, analyticsTotal(w.rows()).signum())
        assertEquals(bd("500"), w.expenses.getValue(1).refundedTotal)
    }

    // ---- old behaviour kept ---------------------------------------------------------------------

    @Test
    fun `a refund with a category and no links still comes off that category as before`() {
        val w = World()
        w.expense(1, "30000"); w.refund(2, "20000", category = "Rent")
        assertMoney("10000", analyticsTotal(w.rows()))
        assertMoney("10000", budgetCategories(w.rows())["Rent"])
    }

    // ---- arithmetic -----------------------------------------------------------------------------

    @Test
    fun `counted amount subtracts the refunded part`() {
        val e = TransactionEntity(
            id = 1, amount = bd("1000"), merchantName = "X", category = "Others", transactionType = TransactionType.EXPENSE,
            dateTime = LocalDateTime.of(2026, 10, 1, 0, 0), transactionHash = "h", refundedTotal = bd("250")
        )
        assertEquals(bd("750"), e.countedAmount())
        assertEquals(bd("750"), e.forTotals()!!.amount)
    }

    @Test
    fun `forTotals is idempotent so a row seen twice is not refunded twice`() {
        val w = World()
        w.expense(1, "30000", parts = mapOf("Rent" to "28000", "Food" to "2000")); w.refund(2, "18000")
        w.link(2, req(1, "18000", "Rent"))
        val once = w.rows().first { it.transaction.id == 1L }.forTotals()!!
        val twice = once.forTotals()!!
        assertEquals(once.transaction.amount, twice.transaction.amount)
        assertEquals(once.splits.map { it.amount }, twice.splits.map { it.amount })
    }

    @Test
    fun `split shares add up exactly to the counted amount after a refund`() {
        val w = World()
        w.expense(1, "100.01", parts = mapOf("A" to "33.34", "B" to "33.34", "C" to "33.33")); w.refund(2, "10.01")
        w.link(2, req(1, "10.01"))
        val counted = w.rows().first { it.transaction.id == 1L }.forTotals()!!
        assertEquals(counted.transaction.amount, counted.splits.fold(BigDecimal.ZERO) { a, s -> a + s.amount })
        assertMoney("90.00", counted.transaction.amount)
        assertTrue(counted.splits.all { it.amount.signum() >= 0 })
    }

    @Test
    fun `a refund on the whole of a split expense shrinks every part in proportion`() {
        val w = World()
        w.expense(1, "1000", parts = mapOf("Rent" to "800", "Food" to "200")); w.refund(2, "500")
        // category null on a split expense: it lands on the whole, since no part is named
        w.link(2, req(1, "500"))
        val counted = w.rows().first { it.transaction.id == 1L }.forTotals()!!
        assertMoney("500", counted.transaction.amount)
        assertMoney("400.00", counted.splits.first { it.category == "Rent" }.amount)
        assertMoney("100.00", counted.splits.first { it.category == "Food" }.amount)
    }

    @Test
    fun `a different-currency expense is never linked or reduced`() {
        val w = World()
        w.expense(1, "100", currency = "USD"); w.refund(2, "50")
        val out = w.link(2, req(1, "50"))
        assertTrue(out.accepted.isEmpty())
        assertEquals(bd("100"), w.expenses.getValue(1).amount - (w.expenses.getValue(1).refundedTotal ?: BigDecimal.ZERO))
    }
}
