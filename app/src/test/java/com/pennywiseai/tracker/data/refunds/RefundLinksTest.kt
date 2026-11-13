package com.pennywiseai.tracker.data.refunds

import com.pennywiseai.tracker.data.database.entity.BudgetImpactType
import com.pennywiseai.tracker.data.database.entity.RefundAllocationEntity
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionSplitEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class RefundLinksTest {
    private fun bd(s: String) = BigDecimal(s)

    private fun expense(
        id: Long, amount: String, currency: String = "INR", type: TransactionType = TransactionType.EXPENSE,
        deleted: Boolean = false, excluded: Boolean = false, loanId: Long? = null, shares: String? = null
    ) = TransactionEntity(
        id = id, amount = bd(amount), merchantName = "Shop$id", category = "Others", transactionType = type,
        dateTime = LocalDateTime.of(2026, 10, 3, 12, 0), transactionHash = "e$id", currency = currency,
        isDeleted = deleted, excludedFromAnalytics = excluded, loanId = loanId, lentSharesTotal = shares?.let(::bd)
    )

    private fun refund(id: Long, amount: String, category: String? = null, currency: String = "INR") = TransactionEntity(
        id = id, amount = bd(amount), merchantName = "Flatmate", category = "Others", transactionType = TransactionType.INCOME,
        dateTime = LocalDateTime.of(2026, 10, 5, 12, 0), transactionHash = "r$id", currency = currency,
        budgetImpactType = BudgetImpactType.DEDUCT_SPENT, budgetCategory = category
    )

    private fun split(id: Long, tx: Long, category: String, amount: String) =
        TransactionSplitEntity(id = id, transactionId = tx, category = category, amount = bd(amount))

    private fun alloc(refundId: Long, expenseId: Long, amount: String, category: String? = null) =
        RefundAllocationEntity(refundId = refundId, expenseId = expenseId, category = category, amount = bd(amount))

    private fun target(e: TransactionEntity, splits: List<TransactionSplitEntity> = emptyList(), others: List<RefundAllocationEntity> = emptyList()) =
        RefundLinks.Target(e, splits, others)

    private fun req(expense: Long, amount: String, category: String? = null) = RefundLinks.Request(expense, category, bd(amount))

    // ---- who can be linked --------------------------------------------------------------------

    @Test
    fun `only a category-less refund is managed by its links`() {
        assertTrue(RefundLinks.isLinkManaged(refund(1, "100")))
        assertFalse(RefundLinks.isLinkManaged(refund(1, "100", category = "Rent")))
        assertFalse(RefundLinks.isLinkManaged(expense(1, "100")))
        assertFalse(RefundLinks.isLinkManaged(refund(1, "100").copy(budgetImpactType = null)))
        assertFalse(RefundLinks.isLinkManaged(refund(1, "100").copy(transactionType = TransactionType.EXPENSE)))
    }

    @Test
    fun `an expense or card purchase can be linked, nothing else`() {
        val r = refund(1, "100")
        assertTrue(RefundLinks.canLink(r, expense(2, "50")))
        assertTrue(RefundLinks.canLink(r, expense(2, "50", type = TransactionType.CREDIT)))
        assertFalse(RefundLinks.canLink(r, expense(2, "50", type = TransactionType.INCOME)))
        assertFalse(RefundLinks.canLink(r, expense(2, "50", type = TransactionType.TRANSFER)))
        assertFalse(RefundLinks.canLink(r, expense(2, "50", type = TransactionType.INVESTMENT)))
    }

    @Test
    fun `an expense in another currency, deleted, excluded, loan-linked or shared cannot be linked`() {
        val r = refund(1, "100")
        assertFalse(RefundLinks.canLink(r, expense(2, "50", currency = "USD")))
        assertFalse(RefundLinks.canLink(r, expense(2, "50", deleted = true)))
        assertFalse(RefundLinks.canLink(r, expense(2, "50", excluded = true)))
        assertFalse(RefundLinks.canLink(r, expense(2, "50", loanId = 9)))
        assertFalse(RefundLinks.canLink(r, expense(2, "50", shares = "20")))
    }

    @Test
    fun `a transaction cannot be linked to itself`() {
        val r = refund(1, "100")
        assertFalse(RefundLinks.canLink(r, r.copy(transactionType = TransactionType.EXPENSE)))
    }

    // ---- planning ------------------------------------------------------------------------------

    @Test
    fun `one refund shared over two expenses`() {
        val a = expense(10, "5000"); val b = expense(11, "3000")
        val out = RefundLinks.plan(refund(1, "8000"), listOf(req(10, "5000"), req(11, "3000")), mapOf(10L to target(a), 11L to target(b)))
        assertEquals(listOf(10L to bd("5000"), 11L to bd("3000")), out.accepted.map { it.expenseId to it.amount })
        assertEquals(bd("8000"), out.total)
        assertTrue(out.problems.isEmpty())
    }

    @Test
    fun `a request is cut to what the expense still has room for`() {
        val a = expense(10, "1000")
        val out = RefundLinks.plan(refund(1, "5000"), listOf(req(10, "5000")), mapOf(10L to target(a)))
        assertEquals(bd("1000"), out.total)
        assertEquals(listOf<RefundLinks.Problem>(RefundLinks.Problem.CutToRoom(10, bd("5000"), bd("1000"))), out.problems)
    }

    @Test
    fun `room already taken by another refund is not available`() {
        val a = expense(10, "30000")
        val taken = listOf(alloc(2, 10, "18000"))
        val out = RefundLinks.plan(refund(1, "20000"), listOf(req(10, "20000")), mapOf(10L to target(a, others = taken)))
        assertEquals(bd("12000"), out.total)
    }

    @Test
    fun `links never exceed the refund itself`() {
        val a = expense(10, "9000"); val b = expense(11, "9000")
        val out = RefundLinks.plan(refund(1, "10000"), listOf(req(10, "9000"), req(11, "9000")), mapOf(10L to target(a), 11L to target(b)))
        assertEquals(bd("10000"), out.total)
        assertTrue(out.problems.any { it is RefundLinks.Problem.OverRefund })
        assertEquals(bd("1000"), out.accepted.last().amount)
    }

    @Test
    fun `once the refund is used up later requests get nothing`() {
        val a = expense(10, "9000"); val b = expense(11, "9000"); val c = expense(12, "9000")
        val out = RefundLinks.plan(
            refund(1, "9000"), listOf(req(10, "9000"), req(11, "500"), req(12, "500")),
            mapOf(10L to target(a), 11L to target(b), 12L to target(c))
        )
        assertEquals(listOf(10L), out.accepted.map { it.expenseId })
        assertEquals(2, out.problems.count { it is RefundLinks.Problem.OverRefund })
    }

    @Test
    fun `zero and negative amounts are refused`() {
        val a = expense(10, "100")
        val out = RefundLinks.plan(refund(1, "50"), listOf(req(10, "0"), req(10, "-5")), mapOf(10L to target(a)))
        assertTrue(out.accepted.isEmpty())
        assertEquals(2, out.problems.count { it is RefundLinks.Problem.NotPositive })
    }

    @Test
    fun `an unknown or unlinkable expense is reported, the rest still go through`() {
        val ok = expense(10, "100"); val usd = expense(11, "100", currency = "USD")
        val out = RefundLinks.plan(refund(1, "100"), listOf(req(99, "10"), req(11, "10"), req(10, "10")), mapOf(10L to target(ok), 11L to target(usd)))
        assertEquals(listOf(10L), out.accepted.map { it.expenseId })
        assertTrue(out.problems.contains(RefundLinks.Problem.UnknownExpense(99)))
        assertTrue(out.problems.contains(RefundLinks.Problem.NotLinkable(11)))
    }

    @Test
    fun `asking twice for the same expense still cannot pass its cost`() {
        val a = expense(10, "1000")
        val out = RefundLinks.plan(refund(1, "5000"), listOf(req(10, "700"), req(10, "700")), mapOf(10L to target(a)))
        assertEquals(bd("1000"), out.total)
    }

    @Test
    fun `a category part takes no more than that category's amount`() {
        val e = expense(10, "30000")
        val parts = listOf(split(1, 10, "Rent", "28000"), split(2, 10, "Food", "2000"))
        val out = RefundLinks.plan(refund(1, "5000"), listOf(req(10, "5000", "Food")), mapOf(10L to target(e, parts)))
        assertEquals(bd("2000"), out.total)
        assertEquals("Food", out.accepted.single().category)
    }

    @Test
    fun `category parts together cannot pass the expense`() {
        val e = expense(10, "30000")
        val parts = listOf(split(1, 10, "Rent", "28000"), split(2, 10, "Food", "2000"))
        val out = RefundLinks.plan(
            refund(1, "40000"), listOf(req(10, "28000", "Rent"), req(10, "2000", "Food"), req(10, "5000")), mapOf(10L to target(e, parts))
        )
        assertEquals(bd("30000"), out.total)
    }

    @Test
    fun `a category the expense does not have becomes a link to the whole expense`() {
        val e = expense(10, "1000")
        val out = RefundLinks.plan(refund(1, "100"), listOf(req(10, "100", "Gone")), mapOf(10L to target(e)))
        assertNull(out.accepted.single().category)
    }

    @Test
    fun `an empty request list unlinks everything`() {
        val out = RefundLinks.plan(refund(1, "100"), emptyList(), emptyMap())
        assertTrue(out.accepted.isEmpty())
        assertEquals(0, out.total.signum())
    }

    @Test
    fun `cents are kept exactly`() {
        val a = expense(10, "100.10")
        val out = RefundLinks.plan(refund(1, "33.33"), listOf(req(10, "33.33")), mapOf(10L to target(a)))
        assertEquals(bd("33.33"), out.total)
    }

    // ---- stored copies -------------------------------------------------------------------------

    @Test
    fun `an expense with no live links stores nothing`() {
        val s = RefundLinks.stored(expense(10, "100"), emptyList(), emptyList())
        assertNull(s.total)
        assertTrue(s.bySplitId.isEmpty())
    }

    @Test
    fun `links add up to the stored total`() {
        val s = RefundLinks.stored(expense(10, "30000"), emptyList(), listOf(alloc(1, 10, "10000"), alloc(2, 10, "8000")))
        assertEquals(bd("18000"), s.total)
    }

    @Test
    fun `an expense edited down after linking is capped at its new cost`() {
        val s = RefundLinks.stored(expense(10, "500"), emptyList(), listOf(alloc(1, 10, "800")))
        assertEquals(bd("500"), s.total)
    }

    @Test
    fun `a category link lands on that split and nowhere else`() {
        val e = expense(10, "30000")
        val parts = listOf(split(1, 10, "Rent", "28000"), split(2, 10, "Food", "2000"))
        val s = RefundLinks.stored(e, parts, listOf(alloc(1, 10, "18000", "Rent")))
        assertEquals(bd("18000"), s.total)
        assertEquals(mapOf(1L to bd("18000")), s.bySplitId)
    }

    @Test
    fun `two splits of one category fill in order and cap each`() {
        val e = expense(10, "300")
        val parts = listOf(split(1, 10, "Food", "100"), split(2, 10, "Food", "200"))
        val s = RefundLinks.stored(e, parts, listOf(alloc(1, 10, "250", "Food")))
        assertEquals(mapOf(1L to bd("100"), 2L to bd("150")), s.bySplitId)
    }

    @Test
    fun `a link to a category that was removed still counts toward the total`() {
        val e = expense(10, "1000")
        val parts = listOf(split(1, 10, "Rent", "1000"))
        val s = RefundLinks.stored(e, parts, listOf(alloc(1, 10, "200", "Removed")))
        assertEquals(bd("200"), s.total)
        assertTrue(s.bySplitId.isEmpty())
    }

    // ---- floater ---------------------------------------------------------------------------------

    @Test
    fun `a refund with no links is all floater`() {
        assertEquals(bd("3000"), RefundLinks.floater(refund(1, "3000"), emptyList()))
    }

    @Test
    fun `the floater is what is left after the links`() {
        val links = listOf(alloc(1, 10, "1200"), alloc(1, 11, "1800.50"))
        assertEquals(bd("0.00"), RefundLinks.floater(refund(1, "3000.50"), links).setScale(2))
        assertEquals(bd("1800.00"), RefundLinks.floater(refund(1, "3000"), listOf(alloc(1, 10, "1200"))).setScale(2))
    }

    @Test
    fun `the floater is never negative`() {
        // the refund was edited down below what it had linked
        assertEquals(0, RefundLinks.floater(refund(1, "500"), listOf(alloc(1, 10, "800"))).signum())
    }
}
