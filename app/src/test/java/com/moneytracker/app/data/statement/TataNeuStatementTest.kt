package com.moneytracker.app.data.statement

import com.moneytracker.parser.core.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

/** The Tata Neu Plus (HDFC) statement layout, with the person's details and real figures replaced. */
class TataNeuStatementTest {
    private val text = """
        TOTAL AMOUNT DUE
        C9,000.00
        MINIMUM DUE
        C450.00
        DUE DATE
        07 Feb, 2026
        NeuCoins with Bank
        120
        Opening NeuCoins with
        Bank
        NeuCoins Earned
        (Base+Bonus)
        NeuCoins Transferred
        to Tata Neu
        Adjusted/Lapsed
        0 120 0 0
        DATE & TIME TRANSACTION DESCRIPTION Base NeuCoins* AMOUNT PI
        CARDHOLDER NAME
        03/01/2026| 10:00 UPI-SOME DAIRY BOOTHNUMBER  C 300.00 l
        13/01/2026| 11:00 SOMEHEALTHSOLUTIO + 10  C 1,000.00 l
        15/01/2026| 12:00 EMI SOME STORE LIMITEDCITY + 80  C 8,000.00 l
        16/01/2026| 10:00 CC PAYMENT RECEIVED (Ref# 0456) +  C 3,000.00 l
        Past Dues
        (if any)
        CURRENT DUES
        C450.00
        MINIMUM DUES
        C450.00
        TOTAL CREDIT LIMIT
        (Including Cash)
        AVAILABLE CREDIT LIMIT AVAILABLE CASH LIMIT
        C50,000 C41,000 C20,000
        Tata Neu Plus HDFC Bank Credit Card Statement
        HDFC Bank Credit Cards GSTIN: 00AAAAA0000A0Z0
        Credit Card No.
        Statement Date
        Billing Period
        000000XXXXXX1234
        17 Jan, 2026
        18 Dec, 2025 - 17 Jan, 2026
    """.trimIndent()

    private val parser = HdfcCreditCardPdfParser()

    @Test fun `the amount to pay is the total due, not the current dues which equal the minimum`() {
        val s = parser.summary(text)!!
        assertEquals(BigDecimal("9000.00"), s.outstanding)
        assertEquals(BigDecimal("450.00"), s.minimumDue)
        assertEquals(LocalDate.of(2026, 2, 7), s.dueDate)
        assertEquals(LocalDate.of(2026, 1, 17), s.statementDate)
        assertEquals(BigDecimal("50000"), s.creditLimit)
        assertEquals(BigDecimal("41000"), s.availableLimit)
        assertEquals("1234", s.cardLast4)
    }

    @Test fun `NeuCoins stay out of the description and a plus before the amount is still a credit`() {
        val rows = parser.parse(text)
        assertEquals(4, rows.size)
        assertEquals("SOMEHEALTHSOLUTIO", rows[1].merchant)
        assertEquals(TransactionType.CREDIT, rows[1].type)
        assertEquals("EMI SOME STORE LIMITEDCITY", rows[2].merchant)
        assertEquals(BigDecimal("8000.00"), rows[2].amount)
        assertEquals(TransactionType.INCOME, rows[3].type)
        assertEquals(BigDecimal("3000.00"), rows[3].amount)
    }

    @Test fun `each row's base NeuCoins and the statement's earned total are read`() {
        val rewards = parser.rowRewardDetails(text)
        assertEquals(listOf(0, 10, 80, 0), rewards.map { it.points.toInt() })
        assertEquals(90, parser.rowRewards(text)!!.toInt())
        assertEquals("120" to true, StatementRewardText.earned(text))
    }

    @Test fun `a statement without a NeuCoins column keeps its rows as they were and has no row rewards`() {
        val plain = """
            TOTAL AMOUNT DUE
            C1,001.00
            MINIMUM DUE
            C1,001.00
            17/01/2026| 00:00 SOME MERCHANT + 25  C 12.50 l
            CURRENT DUES
            C1,000.40
            18 Dec, 2025 - 17 Jan, 2026
        """.trimIndent()
        assertTrue(parser.rowRewardDetails(plain).isEmpty())
        assertNull(parser.rowRewards(plain))
        // Without the Base NeuCoins header a "+" before the amount is only ever a credit marker.
        assertEquals(BigDecimal("1001.00"), parser.summary(plain)!!.outstanding)
    }

    @Test fun `an older layout with only current dues still reads it`() {
        val old = "CURRENT DUES\nC1,000.40\n18 Dec, 2025 - 17 Jan, 2026"
        assertEquals(BigDecimal("1000.40"), parser.summary(old)!!.outstanding)
    }
}
