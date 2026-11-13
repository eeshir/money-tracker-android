package com.pennywiseai.tracker.data.mapper

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import com.pennywiseai.tracker.data.database.entity.TransactionType as EntityType

/**
 * Paying a card's bill moves money between the person's own accounts, so neither the
 * bank-side debit nor the card's "payment received" row may count as spending or income —
 * the card's own spends are what count. Equally, no real spend may be mistaken for one.
 */
class CardPaymentMappingTest {

    private fun parsed(
        type: TransactionType,
        merchant: String?,
        sms: String,
        amount: String = "4000.00",
        isFromCard: Boolean = false
    ) = ParsedTransaction(
        amount = BigDecimal(amount), type = type, merchant = merchant, reference = null,
        accountLast4 = "1234", balance = null, smsBody = sms, sender = "SAMPLE", timestamp = 1_793_000_000_000L,
        bankName = "Sample Bank", isFromCard = isFromCard
    )

    // --- payments that must be left out ---

    @Test
    fun `an ICICI net-banking debit to the person's own card is a card payment, not spending`() {
        val entity = parsed(
            TransactionType.EXPENSE, "Inft*fj49",
            "ICICI Bank Acc XX1234 debited Rs. 4,000.00 on 13-Oct-26 InfoBIL*INFT*FJH7.Avl Bal Rs. 24,000.00.To dispute call 1800"
        ).toEntity()
        assertTrue(entity.excludedFromAnalytics)
        assertEquals("Credit Card Payment", entity.category)
        assertEquals("ICICI Credit Card Payment", entity.merchantName)
        // The bank balance still falls by the payment.
        assertEquals(EntityType.EXPENSE, entity.transactionType)
    }

    @Test
    fun `a card statement's payment received row is not income`() {
        val entity = parsed(
            TransactionType.INCOME, "INFINITY PAYMENT RECEIVED, THANK YOU", "04/10/2026 · card 5678 · payment · 4000.00",
            isFromCard = true
        ).toEntity()
        assertTrue(entity.excludedFromAnalytics)
        assertEquals(EntityType.INCOME, entity.transactionType)
    }

    @Test
    fun `the CRED app and BillDesk with a card in the message are card payments`() {
        assertTrue(parsed(TransactionType.EXPENSE, "CRED Club", "debited; CRED Club credited. UPI:123456789012").toEntity().excludedFromAnalytics)
        assertTrue(parsed(TransactionType.EXPENSE, "Cred", "debited; Cred credited. UPI:123456789012").toEntity().excludedFromAnalytics)
        assertTrue(isCreditCardPayment("BILLDESK", "Payment for your credit card ending 1234 via BillDesk", TransactionType.EXPENSE.toEntityType()))
    }

    // --- real money that must stay counted ---

    @Test
    fun `an ordinary purchase and a refund are still counted`() {
        val purchase = parsed(TransactionType.EXPENSE, "SAMPLE STORE", "Acct XX1234 debited for Rs 400.00; SAMPLE STORE credited. UPI:123456789012").toEntity()
        assertFalse(purchase.excludedFromAnalytics)
        val refund = parsed(TransactionType.INCOME, "SAMPLE STORE", "Refund of Rs 400.00 from SAMPLE STORE credited to your card", isFromCard = true).toEntity()
        assertFalse(refund.excludedFromAnalytics)
    }

    @Test
    fun `a merchant that merely contains the letters cred is not the CRED app`() {
        // A mandate fee and a cashback reward were both hidden by the substring rule.
        val fee = parsed(TransactionType.EXPENSE, "PaisabazaarCred", "Rs 49.00 debited towards PaisabazaarCred for Create+Mandate", amount = "49.00").toEntity()
        assertFalse(fee.excludedFromAnalytics)
        val cashback = parsed(TransactionType.INCOME, "Cashback Credit", "26SEP CASHBACK CREDIT 1,201.00 CR", amount = "1201.00", isFromCard = true).toEntity()
        assertFalse(cashback.excludedFromAnalytics)
        assertFalse(isCreditCardPayment("Credila Finance", "debited for loan EMI", EntityType.EXPENSE))
    }

    @Test
    fun `a BillDesk bill without a card in the message is a real bill`() {
        val bill = parsed(TransactionType.EXPENSE, "BILLDESK", "Rs 1,800.00 debited for electricity bill via BillDesk", amount = "1800.00").toEntity()
        assertFalse(bill.excludedFromAnalytics)
    }

    @Test
    fun `other transfers that merely look similar are not mistaken for card payments`() {
        assertFalse(isIciciCardBillPay("ICICI Bank Acct XX1234 debited for Rs 100.00; SOME SHOP credited. UPI:123456789012"))
        assertFalse(isIciciCardBillPay(null))
        assertTrue(isIciciCardBillPay("… debited Rs. 1.00 on 01-Jan-26 InfoBIL*INFT*FJ49."))
        // "payment received" on a plain account is a person paying you, i.e. income.
        assertFalse(parsed(TransactionType.EXPENSE, "payment received", "x").toEntity().excludedFromAnalytics)
    }
}
