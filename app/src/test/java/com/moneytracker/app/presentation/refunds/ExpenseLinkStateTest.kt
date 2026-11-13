package com.moneytracker.app.presentation.refunds

import com.moneytracker.app.data.database.entity.BudgetImpactType
import com.moneytracker.app.data.database.entity.RefundAllocationEntity
import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class ExpenseLinkStateTest {
    private fun bd(s: String) = BigDecimal(s)

    private val expense = TransactionEntity(
        id = 10, amount = bd("1000"), merchantName = "Landlord", category = "Rent", transactionType = TransactionType.EXPENSE,
        dateTime = LocalDateTime.of(2026, 10, 3, 12, 0), transactionHash = "e"
    )

    private fun refund(id: Long, amount: String) = TransactionEntity(
        id = id, amount = bd(amount), merchantName = "Friend$id", category = "Others", transactionType = TransactionType.INCOME,
        dateTime = LocalDateTime.of(2026, 10, 5, 12, 0), transactionHash = "r$id", budgetImpactType = BudgetImpactType.DEDUCT_SPENT
    )

    private fun alloc(refundId: Long, expenseId: Long, amount: String, category: String? = null) =
        RefundAllocationEntity(refundId = refundId, expenseId = expenseId, category = category, amount = bd(amount))

    private fun state(
        refunds: List<TransactionEntity>, allocations: List<RefundAllocationEntity> = emptyList(), inputs: Map<Long, String> = emptyMap(),
        parts: List<String?> = listOf(null), costs: Map<String?, BigDecimal> = mapOf(null to bd("1000")), part: String? = null
    ) = ExpenseLinkState(expense = expense, parts = parts, costOfPart = costs, part = part, refunds = refunds, allocations = allocations, inputs = inputs)

    @Test
    fun `a refund with money left is offered, one fully used elsewhere is not`() {
        val s = state(listOf(refund(1, "500"), refund(2, "300")), listOf(alloc(2, 99, "300")))
        assertEquals(listOf(1L), s.options.map { it.refund.id })
    }

    @Test
    fun `what is available is the floater, plus what it already puts here`() {
        val s = state(listOf(refund(1, "500")), listOf(alloc(1, 10, "200"), alloc(1, 99, "100")))
        assertEquals(bd("400"), s.options.single().available)
        assertEquals(mapOf(1L to "200"), s.existing())
    }

    @Test
    fun `a refund already linked here stays offered even with no floater`() {
        val s = state(listOf(refund(1, "500")), listOf(alloc(1, 10, "500")))
        assertEquals(1, s.options.size)
        assertEquals(bd("500"), s.options.single().available)
    }

    @Test
    fun `typed amounts add up and cannot pass the cost`() {
        val s = state(listOf(refund(1, "800"), refund(2, "800")), inputs = mapOf(1L to "700", 2L to "400"))
        assertEquals(bd("1100"), s.typedTotal)
        assertTrue(s.overCost)
        assertFalse(s.canSave)
        assertTrue(state(listOf(refund(1, "800"), refund(2, "800")), inputs = mapOf(1L to "700", 2L to "300")).canSave)
    }

    @Test
    fun `an amount past what the refund has available blocks saving`() {
        val s = state(listOf(refund(1, "500")), inputs = mapOf(1L to "600"))
        assertTrue(s.overRefund)
        assertFalse(s.canSave)
    }

    @Test
    fun `blank, zero and junk inputs count as nothing`() {
        val s = state(listOf(refund(1, "500")), inputs = mapOf(1L to "abc"))
        assertEquals(0, s.typedTotal.signum())
        assertTrue(s.canSave)
        assertEquals(0, state(listOf(refund(1, "500")), inputs = mapOf(1L to "0")).typedTotal.signum())
        assertEquals(bd("12.50"), state(listOf(refund(1, "500")), inputs = mapOf(1L to " 1,2.50 ")).typedTotal)
    }

    @Test
    fun `a split expense is linked one part at a time`() {
        val costs = mapOf<String?, BigDecimal>("Rent" to bd("800"), "Food" to bd("200"))
        val parts = listOf<String?>("Rent", "Food")
        val allocs = listOf(alloc(1, 10, "300", "Rent"), alloc(1, 10, "50", "Food"))
        val rent = state(listOf(refund(1, "500")), allocs, parts = parts, costs = costs, part = "Rent")
        val food = rent.copy(part = "Food")
        assertEquals(bd("800"), rent.cost)
        assertEquals(mapOf(1L to "300"), rent.existing())
        assertEquals(bd("200"), food.cost)
        assertEquals(mapOf(1L to "50"), food.existing())
        // available on a part = floater (500 - 350) + what it already puts on that part
        assertEquals(bd("450"), rent.options.single().available)
        assertEquals(bd("200"), food.options.single().available)
    }

    @Test
    fun `nothing to offer means nothing to save`() {
        val s = state(emptyList())
        assertTrue(s.options.isEmpty())
        assertTrue(s.canSave)
    }
}
