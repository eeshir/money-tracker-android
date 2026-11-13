package com.moneytracker.app.domain.spending

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class CardDueRulesTest {
    private val today = LocalDate.of(2026, 10, 31)

    private fun item(
        due: LocalDate = today.plusDays(2),
        total: String = "10000",
        min: String? = "500",
        paid: String = "0",
        marked: Boolean = false
    ) = CardDueRules.item(
        key = "k", cardLabel = "Card ••1234", currency = "INR", dueDate = due,
        totalDue = BigDecimal(total), minimumDue = min?.let(::BigDecimal),
        paidSince = BigDecimal(paid), markedPaid = marked, today = today
    )

    @Test
    fun `an unpaid bill shows with days left`() {
        val i = item()!!
        assertEquals(2, i.daysLeft)
        assertEquals(BigDecimal("10000"), i.remaining)
        assertFalse(i.minimumPaid)
    }

    @Test
    fun `a full payment hides it`() = assertNull(item(paid = "10000"))

    @Test
    fun `marked paid hides it`() = assertNull(item(marked = true))

    @Test
    fun `a part payment shows what is left and notes the minimum`() {
        val i = item(paid = "600")!!
        assertEquals(BigDecimal("9400"), i.remaining)
        assertTrue(i.minimumPaid)
    }

    @Test
    fun `overdue stays for a month then drops`() {
        assertTrue(item(due = today.minusDays(5))!!.isOverdue)
        assertNotNull(item(due = today.minusDays(30)))
        assertNull(item(due = today.minusDays(31)))
    }
}
