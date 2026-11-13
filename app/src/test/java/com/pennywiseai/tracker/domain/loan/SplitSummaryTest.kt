package com.pennywiseai.tracker.domain.loan

import com.pennywiseai.tracker.data.database.entity.LoanDirection
import com.pennywiseai.tracker.data.database.entity.LoanEntity
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class SplitSummaryTest {
    private fun d(v: String) = BigDecimal(v)

    private fun loan(id: Long, person: String, remaining: String, currency: String = "INR", dir: LoanDirection = LoanDirection.LENT) =
        LoanEntity(id = id, personName = person, direction = dir, originalAmount = d(remaining), remainingAmount = d(remaining), currency = currency)

    private fun bill(id: Long, name: String, amount: String, share: String? = null, day: Int = 1) =
        TransactionEntity(
            id = id, amount = d(amount), merchantName = name, category = "Food",
            transactionType = TransactionType.EXPENSE,
            dateTime = LocalDateTime.of(2026, 10, day, 20, 0), transactionHash = "h$id", currency = "INR",
            loanContribution = share?.let(::d)
        )

    @Test
    fun `one person's shares across several bills become one total`() {
        val out = SplitSummary.compile(
            listOf(
                loan(1, "Asha", "500") to listOf(bill(1, "Dinner", "1500", "200", 1), bill(2, "Cab", "600", "100", 2), bill(3, "Movie", "900", "200", 3)),
                loan(2, "Ravi", "300") to listOf(bill(1, "Dinner", "1500", "300", 1))
            )
        )
        assertEquals(listOf("Asha", "Ravi"), out.map { it.person })
        val asha = out.first()
        assertEquals(d("500"), asha.total)
        assertEquals(listOf(d("200"), d("100"), d("200")), asha.lines.map { it.amount })
    }

    @Test
    fun `currencies are never merged for one person`() {
        val out = SplitSummary.compile(listOf(loan(1, "Asha", "500") to emptyList(), loan(2, "Asha", "10", "USD") to emptyList()))
        assertEquals(2, out.size)
        assertEquals(setOf("INR", "USD"), out.map { it.currency }.toSet())
    }

    @Test
    fun `borrowed and settled-down loans are left out`() {
        val out = SplitSummary.compile(
            listOf(
                loan(1, "Asha", "500", dir = LoanDirection.BORROWED) to emptyList(),
                loan(2, "Ravi", "0") to emptyList()
            )
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `request text carries every bill and one total`() {
        val owed = SplitSummary.compile(listOf(loan(1, "Asha", "300") to listOf(bill(1, "Dinner", "900", "200"), bill(2, "Cab", "300", "100", 2)))).single()
        val text = SplitSummary.requestText(owed, "upi://pay?pa=x@y", "x@y")
        assertTrue(text.contains("Dinner"))
        assertTrue(text.contains("Cab"))
        assertEquals(1, Regex("Total:").findAll(text).count())
        assertTrue(text.endsWith("upi://pay?pa=x@y"))
    }
}
