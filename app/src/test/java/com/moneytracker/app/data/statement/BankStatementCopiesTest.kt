package com.moneytracker.app.data.statement

import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class BankStatementCopiesTest {
    private val imported = LocalDateTime.of(2026, 7, 20, 10, 0)

    private fun sms(id: Long, amount: String, day: Int, type: TransactionType = TransactionType.INCOME, account: String = "111", month: Int = 6) =
        TransactionEntity(
            id = id, amount = BigDecimal(amount), merchantName = "Sender", category = "x", transactionType = type,
            dateTime = LocalDateTime.of(2026, month, day, 7, 0), bankName = "Bank A", accountNumber = account,
            smsSender = "BANKSMS", smsBody = "credited $id", transactionHash = "h$id"
        )

    private fun row(id: Long, amount: String, day: Int, type: TransactionType = TransactionType.INCOME) =
        TransactionEntity(
            id = id, amount = BigDecimal(amount), merchantName = "Sender Limited", category = "x", transactionType = type,
            dateTime = LocalDateTime.of(2026, 6, day, 12, 0), bankName = "Bank A", accountNumber = null,
            smsSender = "BANKA PDF", transactionHash = "h$id", createdAt = imported
        )

    private fun dropped(vararg t: TransactionEntity) = StatementDuplicates.find(t.toList()).map { it.drop.id }.sorted()

    @Test fun `repeated transfers pair one to one even when the SMS trail by a day or two`() {
        val found = StatementDuplicates.find(
            listOf(row(1, "5000", 8), row(2, "5000.00", 8), row(3, "5000", 8), row(4, "5000", 9), row(5, "5000", 9)) +
                listOf(sms(11, "5000", 9), sms(12, "5000", 9), sms(13, "5000", 9), sms(14, "5000", 10), sms(15, "5000", 10))
        )
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), found.map { it.drop.id }.sorted())
        assertEquals(5, found.map { it.keep.id }.toSet().size)
    }

    @Test fun `a statement listing three keeps the third when only two have an SMS`() {
        assertEquals(2, dropped(row(1, "5000", 2), row(2, "5000", 2), row(3, "5000", 2), sms(11, "5000", 2), sms(12, "5000", 2)).size)
    }

    @Test fun `money out saved as an investment matches the statement's expense`() {
        assertEquals(
            listOf(1L),
            dropped(row(1, "5000", 4, TransactionType.EXPENSE), sms(11, "5000", 4, TransactionType.INVESTMENT))
        )
    }

    @Test fun `a card SMS is never taken for a bank statement row`() {
        val cardBuy = sms(11, "400", 4, TransactionType.CREDIT, account = "222")
        assertTrue(dropped(row(1, "400", 4, TransactionType.EXPENSE), cardBuy).isEmpty())
        // A refund on that card is money in, but still the card's.
        assertTrue(dropped(row(2, "90", 4), cardBuy, sms(12, "90", 4, account = "222")).isEmpty())
    }

    @Test fun `an SMS that arrived after the statement was imported removes nothing`() {
        val last = row(1, "20", 30)
        assertTrue(dropped(last, sms(11, "20", 21, month = 7)).isEmpty())
        assertTrue(dropped(last.copy(createdAt = LocalDateTime.of(2026, 6, 30, 13, 0)), sms(11, "20", 1, month = 7)).isEmpty())
    }

    @Test fun `a card refund SMS may trail the card statement by a few days`() {
        fun card(id: Long, day: Int) = row(id, "150", day).copy(accountNumber = "222", smsSender = "BANKA CC PDF")
        assertEquals(listOf(1L), dropped(card(1, 21), sms(11, "150", 23, account = "222")))
        assertTrue(dropped(card(1, 10), sms(11, "150", 23, account = "222")).isEmpty())
    }

    @Test fun `a UPI row quoting another payment's number is not that SMS, whatever the amount and date`() {
        fun upi(id: Long, number: String) = row(id, "22", 8, TransactionType.EXPENSE).copy(smsBody = "08-06-2026 UPI/Shop/shop@x/Pay/BANKP/$number/abc")
        fun paid(id: Long, number: String) = sms(id, "22", 8, TransactionType.EXPENSE).copy(reference = number, smsBody = "debited UPI:$number")
        assertTrue(dropped(upi(1, "111111111111"), paid(11, "222222222222")).isEmpty())
        // The row that quotes the number wins over an earlier one that cannot tell.
        val vague = row(2, "22", 8, TransactionType.EXPENSE)
        assertEquals(listOf(1L), dropped(vague, upi(1, "333333\n333333"), paid(11, "333333333333")))
    }

    @Test fun `a card's balance sent to the bank is not the refund on its statement`() {
        val refund = row(1, "3000", 23).copy(accountNumber = "222", smsSender = "BANKA CC PDF")
        val transfer = sms(11, "3000", 25, account = "222")
            .copy(smsBody = "Refund of Rs 3,000.00 from Bank A Credit Card XX222 to Savings Account XX111 has been successfully transferred.")
        assertTrue(dropped(refund, transfer).isEmpty())
    }

    @Test fun `after a copy is removed, its SMS is not paired with another statement row`() {
        // Two payments each on 8 and 9 June. The 9th's SMS already replaced their own copies.
        val removed = listOf(row(3, "5000", 9).copy(isDeleted = true), row(4, "5000", 9).copy(isDeleted = true))
        val live = listOf(row(1, "5000", 8), row(2, "5000", 8), sms(13, "5000", 9), sms(14, "5000", 9))
        assertTrue(dropped(*(removed + live).toTypedArray()).isEmpty())
    }
}
