package com.moneytracker.app.data.mapper

import com.moneytracker.parser.core.ParsedTransaction
import com.moneytracker.parser.core.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/** One brand, one merchant row — however the card network spells it. */
class MerchantGroupingTest {

    @Test
    fun `every spelling of Blinkit is Blinkit`() {
        for (name in listOf("Blinkit", "BLINKIT", "Blink Commerce Pvt Ltd", "IAP BLINK COMMERCE PVT LTD BANGA", "Grofers India")) {
            assertEquals(name, "Blinkit", canonicalizeMerchantName(name))
        }
    }

    @Test
    fun `every Amazon Pay spelling is Amazon, but Prime and AWS are separate`() {
        for (name in listOf("Amazon Pay In E", "AMAZON PAY IN RECHARGE", "Amazon Pay Indi", "Amazon Pay Transaction", "Amazon Pay Bala", "WWW AMAZON IN", "Amazon")) {
            assertEquals(name, "Amazon", canonicalizeMerchantName(name))
        }
        assertEquals("Amazon Prime Video", canonicalizeMerchantName("Amazon Prime Video"))
        assertEquals("Amazon Web Services", canonicalizeMerchantName("Amazon Web Services"))
    }

    @Test
    fun `Myntra variants are one merchant`() {
        for (name in listOf("Myntra Designs Private L", "MYNTRA DESIGNS PVT LTD", "Myntra Via Smartbuy")) {
            assertEquals(name, "Myntra", canonicalizeMerchantName(name))
        }
    }

    @Test
    fun `unrelated merchants are left alone`() {
        assertEquals("Sample Shop", canonicalizeMerchantName("Sample Shop"))
        assertEquals("Blink Studio", canonicalizeMerchantName("Blink Studio")) // "blink" alone is not Blinkit
    }

    @Test
    fun `a Blinkit purchase under the company name lands in Groceries, not Others`() {
        val entity = ParsedTransaction(
            amount = BigDecimal("300.00"), type = TransactionType.CREDIT, merchant = "Blink Commerce Pvt Ltd", reference = null,
            accountLast4 = "1234", balance = null, smsBody = "Card used at Blink Commerce Pvt Ltd", sender = "SAMPLE",
            timestamp = 1_793_000_000_000L, bankName = "Sample Bank", isFromCard = true
        ).toEntity()
        assertEquals("Blinkit", entity.merchantName)
        assertEquals("Groceries", entity.category)
    }
}
