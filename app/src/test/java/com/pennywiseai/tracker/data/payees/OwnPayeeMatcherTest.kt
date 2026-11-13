package com.pennywiseai.tracker.data.payees

import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class OwnPayeeMatcherTest {

    private fun tx(
        id: Long,
        type: TransactionType,
        amount: String,
        bank: String,
        account: String,
        payee: String = "Someone",
        at: LocalDateTime = LocalDateTime.of(2026, 10, 11, 12, 0)
    ) = TransactionEntity(
        id = id, amount = BigDecimal(amount), merchantName = payee, category = "Others", transactionType = type,
        dateTime = at, bankName = bank, accountNumber = account, transactionHash = "h$id"
    )

    private val self = OwnPayeeRule(payee = "sample person", sourceBank = "Bank A", sourceLast4 = "1111", targetBank = "Bank B", targetLast4 = "2222")
    private val cardBill = OwnPayeeRule(payee = "card bill svc", targetBank = "Bank C", targetLast4 = "3333")
    private val anyCard = OwnPayeeRule(payee = "pay hub")

    // --- which rule applies ---

    @Test
    fun `a debit to a named payee from the named account matches, case-insensitively`() {
        val debit = tx(1, TransactionType.EXPENSE, "3000", "bank a", "1111", payee = "Sample Person")
        assertEquals(self, OwnPayeeMatcher.ruleFor(listOf(self), debit))
    }

    @Test
    fun `the same payee from a different account does not match a rule that names the source`() {
        val debit = tx(1, TransactionType.EXPENSE, "3000", "Bank A", "9999", payee = "Sample Person")
        assertNull(OwnPayeeMatcher.ruleFor(listOf(self), debit))
    }

    @Test
    fun `only money going out matches`() {
        assertNull(OwnPayeeMatcher.ruleFor(listOf(self), tx(1, TransactionType.INCOME, "3000", "Bank A", "1111", payee = "Sample Person")))
    }

    @Test
    fun `the rule naming the source account beats one for any account`() {
        val general = OwnPayeeRule(payee = "sample person", targetBank = "Bank X", targetLast4 = "0000")
        val debit = tx(1, TransactionType.EXPENSE, "10", "Bank A", "1111", payee = "Sample Person")
        assertEquals(self, OwnPayeeMatcher.ruleFor(listOf(general, self), debit))
    }

    @Test
    fun `a blank payee never matches everything`() {
        assertNull(OwnPayeeMatcher.ruleFor(listOf(OwnPayeeRule(payee = "")), tx(1, TransactionType.EXPENSE, "10", "Bank A", "1111")))
    }

    // --- the other side of the payment ---

    @Test
    fun `the credit of the same amount on the target account is the counterpart`() {
        val debit = tx(1, TransactionType.EXPENSE, "3000", "Bank A", "1111", payee = "Sample Person")
        val credit = tx(2, TransactionType.INCOME, "3000", "Bank B", "2222", at = debit.dateTime.plusHours(2))
        val other = tx(3, TransactionType.INCOME, "3000", "Bank B", "7777")
        assertEquals(credit, OwnPayeeMatcher.counterpartOf(debit, self, listOf(other, credit), emptySet()))
    }

    @Test
    fun `a rule with no target pairs with a matching credit on any other account`() {
        val debit = tx(1, TransactionType.EXPENSE, "4500.25", "Bank A", "1111", payee = "Pay Hub")
        val payment = tx(2, TransactionType.INCOME, "4500.25", "Card Bank", "3333", at = debit.dateTime.plusDays(1))
        assertEquals(payment, OwnPayeeMatcher.counterpartOf(debit, anyCard, listOf(payment), emptySet()))
    }

    @Test
    fun `a credit in the same account, of another amount, or too far away is not the counterpart`() {
        val debit = tx(1, TransactionType.EXPENSE, "3000", "Bank A", "1111", payee = "Sample Person")
        val candidates = listOf(
            tx(2, TransactionType.INCOME, "3000", "Bank A", "1111"),
            tx(3, TransactionType.INCOME, "2999.99", "Bank B", "2222"),
            tx(4, TransactionType.INCOME, "3000", "Bank B", "2222", at = debit.dateTime.plusDays(5)),
            tx(5, TransactionType.EXPENSE, "3000", "Bank B", "2222")
        )
        assertNull(OwnPayeeMatcher.counterpartOf(debit, self, candidates, emptySet()))
    }

    @Test
    fun `a credit already paired with another debit is not used twice`() {
        val debit = tx(1, TransactionType.EXPENSE, "3000", "Bank A", "1111", payee = "Sample Person")
        val credit = tx(2, TransactionType.INCOME, "3000", "Bank B", "2222")
        assertNull(OwnPayeeMatcher.counterpartOf(debit, self, listOf(credit), taken = setOf(2L)))
    }

    @Test
    fun `of two candidates the closest in time wins`() {
        val debit = tx(1, TransactionType.EXPENSE, "500", "Bank A", "1111", payee = "Card Bill Svc")
        val near = tx(2, TransactionType.INCOME, "500", "Bank C", "3333", at = debit.dateTime.plusHours(1))
        val far = tx(3, TransactionType.INCOME, "500", "Bank C", "3333", at = debit.dateTime.plusDays(2))
        assertEquals(near, OwnPayeeMatcher.counterpartOf(debit, cardBill, listOf(far, near), emptySet()))
    }
}
