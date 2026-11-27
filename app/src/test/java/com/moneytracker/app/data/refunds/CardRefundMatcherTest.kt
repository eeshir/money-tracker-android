package com.moneytracker.app.data.refunds

import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import com.moneytracker.app.data.statement.CardPaymentPairs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class CardRefundMatcherTest {
    private fun tx(
        id: Long, amount: String, merchant: String, type: TransactionType, day: Int,
        sender: String = "SMS", excluded: Boolean = false, account: String = "1234"
    ) = TransactionEntity(
        id = id, amount = BigDecimal(amount), merchantName = merchant, category = "Others", transactionType = type,
        dateTime = LocalDateTime.of(2026, 8, day, 12, 0), transactionHash = "h$id", bankName = "Bank",
        accountNumber = account, smsSender = sender, excludedFromAnalytics = excluded
    )

    @Test fun `refund is split over the purchase of the same merchant`() {
        val buy = tx(1, "21000", "Myntra", TransactionType.CREDIT, 27)
        val other = tx(2, "500", "Zomato", TransactionType.CREDIT, 28)
        val refund = tx(3, "8100", "Myntra", TransactionType.INCOME, 31)
        val plan = CardRefundMatcher.plan(refund, listOf(buy, other))
        assertEquals(listOf(1L), plan.map { it.expenseId })
        assertEquals(BigDecimal("8100"), plan.single().amount)
    }

    @Test fun `exact amount purchase wins over a later one`() {
        val exact = tx(1, "262", "Swiggy", TransactionType.CREDIT, 10)
        val later = tx(2, "400", "Swiggy", TransactionType.CREDIT, 16)
        val plan = CardRefundMatcher.plan(tx(3, "262", "Swiggy", TransactionType.INCOME, 17), listOf(exact, later))
        assertEquals(1L, plan.first().expenseId)
    }

    @Test fun `room used by an earlier refund is respected`() {
        val buy = tx(1, "1000", "Myntra", TransactionType.CREDIT, 1)
        val used = HashMap<Long, BigDecimal>()
        CardRefundMatcher.plan(tx(2, "700", "Myntra", TransactionType.INCOME, 5), listOf(buy), used)
        val second = CardRefundMatcher.plan(tx(3, "700", "Myntra", TransactionType.INCOME, 6), listOf(buy), used)
        assertEquals(BigDecimal("300"), second.single().amount)
    }

    @Test fun `no match for another card, another merchant or a later purchase`() {
        val refund = tx(9, "100", "Myntra", TransactionType.INCOME, 10)
        assertTrue(CardRefundMatcher.plan(refund, listOf(tx(1, "100", "Myntra", TransactionType.CREDIT, 5, account = "9999"))).isEmpty())
        assertTrue(CardRefundMatcher.plan(refund, listOf(tx(1, "100", "Nykaa", TransactionType.CREDIT, 5))).isEmpty())
        assertTrue(CardRefundMatcher.plan(refund, listOf(tx(1, "100", "Myntra", TransactionType.CREDIT, 20))).isEmpty())
    }

    @Test fun `payments cashback and excluded rows are not merchant refunds`() {
        assertTrue(CardRefundMatcher.isUnlinkedMerchantRefund(tx(1, "10", "Zomato", TransactionType.INCOME, 1)))
        assertFalse(CardRefundMatcher.isUnlinkedMerchantRefund(tx(1, "10", "Cashback Credit", TransactionType.INCOME, 1)))
        assertFalse(CardRefundMatcher.isUnlinkedMerchantRefund(tx(1, "10", "Credit Card Payment", TransactionType.INCOME, 1)))
        assertFalse(CardRefundMatcher.isUnlinkedMerchantRefund(tx(1, "10", "Zomato", TransactionType.INCOME, 1, excluded = true)))
    }

    @Test fun `a refund already entered by hand is left alone`() {
        val bank = tx(1, "1040.25", "Reliance Retail", TransactionType.INCOME, 2, sender = "HSBC CC PDF")
        val manual = tx(2, "1040.25", "Ajio", TransactionType.INCOME, 11).copy(
            bankName = "Manual Entry", accountNumber = null,
            budgetImpactType = com.moneytracker.app.data.database.entity.BudgetImpactType.DEDUCT_SPENT
        )
        assertTrue(CardRefundMatcher.enteredByHand(bank, listOf(bank, manual)))
        assertFalse(CardRefundMatcher.enteredByHand(bank, listOf(bank, manual.copy(amount = BigDecimal("900")))))
    }

    @Test fun `an HSBC BBPS statement row is a card payment, even one imported before it was recognised`() {
        assertFalse(CardRefundMatcher.isUnlinkedMerchantRefund(tx(1, "17010.55", "BBPS PMT BBPSAZ01", TransactionType.INCOME, 2)))
        val old = tx(1, "17010.55", "BBPS PMT BBPSAZ01", TransactionType.INCOME, 2, sender = "HSBC CC PDF")
        val sms = tx(2, "17010.55", "credit card ending 1234", TransactionType.INCOME, 4, sender = "TX-HSBCIM-S", excluded = true)
        assertEquals(mapOf(1L to 2L), CardPaymentPairs.duplicatesToKeepers(listOf(old, sms)))
    }

    @Test fun `statement payment row is a duplicate of the sms a few days later`() {
        val statement = tx(1, "10500.75", "BBPS PMT", TransactionType.INCOME, 3, sender = "HSBC CC PDF", excluded = true)
        val sms = tx(2, "10500.75", "Credit Card Payment", TransactionType.INCOME, 6, sender = "VM-HSBCIM-S", excluded = true)
        assertEquals(mapOf(1L to 2L), CardPaymentPairs.duplicatesToKeepers(listOf(statement, sms)))
        val far = sms.copy(dateTime = LocalDateTime.of(2026, 8, 20, 9, 0))
        assertTrue(CardPaymentPairs.duplicatesToKeepers(listOf(statement, far)).isEmpty())
    }
}
