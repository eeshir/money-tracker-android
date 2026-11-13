package com.moneytracker.app.data.statement

import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class StatementDuplicatesTest {

    private fun tx(id: Long, amount: String, sender: String?, day: Int = 29, merchant: String = "Shop", account: String = "1234") =
        TransactionEntity(
            id = id, amount = BigDecimal(amount), merchantName = merchant, category = "x", transactionType = TransactionType.CREDIT,
            dateTime = LocalDateTime.of(2026, 6, day, if (sender?.endsWith("CC PDF") == true) 12 else 9, 0),
            bankName = "Bank A", accountNumber = account, smsSender = sender, transactionHash = "h$id"
        )

    @Test
    fun `an SMS row and a statement row for the same card, day and amount are a duplicate, even with different merchant spellings`() {
        val found = StatementDuplicates.find(listOf(tx(1, "155.00", "BANKSMS", merchant = "Swiggy"), tx(2, "155", "Bank A CC PDF", merchant = "swiggy ltd d")))
        assertEquals(listOf(1L to 2L), found.map { it.keep.id to it.drop.id })
    }

    @Test
    fun `pairs are one to one so a real second purchase is not removed`() {
        val found = StatementDuplicates.find(
            listOf(tx(1, "155", "BANKSMS"), tx(2, "155", "BANKSMS"), tx(3, "155", "Bank A CC PDF"))
        )
        assertEquals(1, found.size)
    }

    @Test
    fun `two days apart, a different amount or a different card is not a duplicate`() {
        val found = StatementDuplicates.find(
            listOf(
                tx(1, "155", "BANKSMS"), tx(2, "155", "Bank A CC PDF", day = 27), tx(3, "156", "Bank A CC PDF"),
                tx(4, "155", "Bank A CC PDF", account = "9999")
            )
        )
        assertEquals(0, found.size)
    }

    @Test
    fun `deleted rows and two rows from the same source are ignored`() {
        val found = StatementDuplicates.find(
            listOf(tx(1, "155", "BANKSMS").copy(isDeleted = true), tx(2, "155", "Bank A CC PDF"), tx(3, "155", "Bank A CC PDF"))
        )
        assertEquals(0, found.size)
    }
}

class StatementDuplicatesAdjacentDayTest {

    private fun tx(id: Long, amount: String, sender: String?, at: LocalDateTime, account: String = "1234") =
        TransactionEntity(
            id = id, amount = BigDecimal(amount), merchantName = "Shop", category = "x", transactionType = TransactionType.CREDIT,
            dateTime = at, bankName = "Bank A", accountNumber = account, smsSender = sender, transactionHash = "h$id"
        )

    @Test
    fun `an SMS just after midnight pairs with the statement row of the day before`() {
        val found = StatementDuplicates.find(
            listOf(
                tx(1, "15355.88", "Bank A CC PDF", LocalDateTime.of(2026, 6, 17, 12, 0)),
                tx(2, "15355.88", "BANKSMS", LocalDateTime.of(2026, 6, 18, 0, 46))
            )
        )
        assertEquals(listOf(2L to 1L), found.map { it.keep.id to it.drop.id })
    }

    @Test
    fun `a same-day pair is made first, so a neighbouring day's rows are left alone`() {
        val found = StatementDuplicates.find(
            listOf(
                tx(1, "100", "Bank A CC PDF", LocalDateTime.of(2026, 6, 17, 12, 0)),
                tx(2, "100", "BANKSMS", LocalDateTime.of(2026, 6, 17, 9, 0)),
                tx(3, "100", "BANKSMS", LocalDateTime.of(2026, 6, 18, 9, 0))
            )
        )
        assertEquals(listOf(2L to 1L), found.map { it.keep.id to it.drop.id })
    }

    @Test
    fun `rows two days apart or on different cards are not paired`() {
        val found = StatementDuplicates.find(
            listOf(
                tx(1, "100", "Bank A CC PDF", LocalDateTime.of(2026, 6, 17, 12, 0)),
                tx(2, "100", "BANKSMS", LocalDateTime.of(2026, 6, 19, 9, 0)),
                tx(3, "100", "BANKSMS", LocalDateTime.of(2026, 6, 18, 9, 0), account = "9999")
            )
        )
        assertEquals(0, found.size)
    }
}
