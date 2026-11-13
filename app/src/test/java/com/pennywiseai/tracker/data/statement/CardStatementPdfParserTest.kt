package com.pennywiseai.tracker.data.statement

import com.pennywiseai.parser.core.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Card statement readers against synthetic text laid out as the banks' PDFs extract. */
class CardStatementPdfParserTest {

    private fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(CardStatementText.IST).toLocalDate()
    private fun money(s: String) = BigDecimal(s)

    private val hdfc = """
        TOTAL AMOUNT DUE
        C1,250.00
        MINIMUM DUE
        C200.00
        DUE DATE
        06 Nov, 2026
        Domestic Transactions
        DATE & TIME TRANSACTION DESCRIPTION AMOUNT PI
        17/09/2026| 00:00 IGST-VPS1111111111-RATE 18.0 -08 (Ref# 01111111111111111111)  C 45.00 l
        18/09/2026| 20:11 CC PAYMENT 222222222222 SOMEAPP (Ref# 02222222222222222222) +  C 3,000.00 l
        01/10/2026| 13:45 SAMPLE STORE CITY (Ref# 03333333333333333333)  C 1,205.00 l
        CURRENT DUES
        C1,250.40
        AVAILABLE CREDIT LIMIT AVAILABLE CASH LIMIT
        C50,000 C40,000 C20,000
        MoneyBack+ Credit Card Statement
        HDFC Bank Credit Cards GSTIN: 00TEST0000T0T0
        Credit Card No.
        Statement Date
        Billing Period
        400000XXXXXX1111
        17 Oct, 2026
        18 Sep, 2026 - 17 Oct, 2026
    """.trimIndent()

    @Test fun `hdfc reads rows, payments as income, and the summary`() {
        val parser = HdfcCreditCardPdfParser()
        assertTrue(parser.canHandle(hdfc))
        val rows = parser.parse(hdfc)
        assertEquals(3, rows.size)
        assertEquals(listOf(TransactionType.CREDIT, TransactionType.INCOME, TransactionType.CREDIT), rows.map { it.type })
        assertEquals(money("3000.00"), rows[1].amount)
        assertTrue(rows.all { it.accountLast4 == "1111" && it.isFromCard && it.bankName == "HDFC Bank" })
        assertEquals("SAMPLE STORE CITY", rows[2].merchant)
        assertEquals(LocalDate.of(2026, 10, 1), dateOf(rows[2].timestamp))

        val summary = parser.summary(hdfc)!!
        assertEquals(LocalDate.of(2026, 10, 17), summary.statementDate)
        assertEquals(money("1250.40"), summary.outstanding)
        assertEquals(money("200.00"), summary.minimumDue)
        assertEquals(LocalDate.of(2026, 11, 6), summary.dueDate)
        assertEquals(money("50000"), summary.creditLimit)
        assertEquals(money("40000"), summary.availableLimit)
    }

    private val icici = """
        PAYMENT DUE DATE
        STATEMENT DATE
        October 5, 2026
        October 23, 2026
        Date SerNo. Transaction Details Reward
        Amount (in`)
        4000XXXXXXXX2222
        10/09/2026 11111111111 SAMPLE SHOP ONLINE CITY
        IN
        14 283.00
        25/09/2026 22222222222 SAMPLE RECHARGE CITY IN 71 1,500.00
        04/10/2026 33333333333 PAYMENT RECEIVED, THANK YOU 0 1,000.00 CR
        Credit Limit (Including cash) Available Credit (Including cash) Cash Limit Available Cash
        `1,00,000.00 `99,217.00 `10,000.00 `10,000.00
        ICICI Bank Credit Card GST Number: 00TEST0000T0T0
        Statement period : September 6, 2026 to October 5, 2026
        CREDIT CARD STATEMENT
        Previous Balance Purchases / Charges Cash Advances Payments / Credits
        `0.00 `1,783.00 `0.00 `1,000.00
    """.trimIndent()

    @Test fun `icici joins wrapped rows and computes the statement balance`() {
        val parser = IciciCreditCardPdfParser()
        assertTrue(parser.canHandle(icici))
        val rows = parser.parse(icici)
        assertEquals(3, rows.size)
        assertEquals("SAMPLE SHOP ONLINE CITY IN", rows[0].merchant)
        assertEquals(money("283.00"), rows[0].amount)
        assertEquals(money("1500.00"), rows[1].amount)
        assertEquals(TransactionType.INCOME, rows[2].type)
        assertTrue(rows.all { it.accountLast4 == "2222" && it.bankName == "ICICI Bank" })

        val summary = parser.summary(icici)!!
        assertEquals(LocalDate.of(2026, 10, 5), summary.statementDate)
        assertEquals(money("783.00"), summary.outstanding)
        assertEquals(LocalDate.of(2026, 10, 23), summary.dueDate)
        assertEquals(money("100000.00"), summary.creditLimit)
        assertEquals(money("99217.00"), summary.availableLimit)
    }

    @Test fun `icici savings statements are not taken for card statements`() {
        val savings = "ICICI Bank\nDATE MODE PARTICULARS DEPOSITS WITHDRAWALS BALANCE\nStatement of Transactions in Savings Account"
        assertTrue(PdfParserFactory.getParser(savings) is IciciBankPdfParser)
        assertTrue(PdfParserFactory.getParser(icici) is IciciCreditCardPdfParser)
    }

    private val hsbc = """
        Opening balance (      ) Purchase & other charges (      ) Payment & other credits (      ) Net Outstanding balance (      )
        Credit limit (       ) Cash limit (       )*
        09 JAN 2027 52.00
        26 DEC 2026  To 25 JAN 2027 2,600.00
        100,000.00 20,000.00
        43xx xxxx xxxx 3333 43xx xxxx xxxx 4444
        HSBC LIVE+ CREDIT CARD
         Available Credit Limit           97,400.00
        OPENING BALANCE 1,000.00
        05JAN BBPS PMT BBPSTEST000 1,000.00 CR

        43xx xxxx xxxx 3333 CARDHOLDER
        28DEC SAMPLE GROCER         CITY          IN 600.00

        10JAN Sample Food           CITY          IN 2,000.00

        12JAN CASHBACK CREDIT 100.00 CR
        25JAN NET OUTSTANDING BALANCE 2,600.00
    """.trimIndent()

    @Test fun `hsbc dates rows across a year end and skips the closing line`() {
        val parser = HsbcCreditCardPdfParser()
        assertTrue(parser.canHandle(hsbc))
        val rows = parser.parse(hsbc)
        assertEquals(4, rows.size)
        assertEquals(LocalDate.of(2026, 12, 28), dateOf(rows[1].timestamp))
        assertEquals(LocalDate.of(2027, 1, 10), dateOf(rows[2].timestamp))
        assertEquals(listOf(TransactionType.INCOME, TransactionType.CREDIT, TransactionType.CREDIT, TransactionType.INCOME), rows.map { it.type })
        assertTrue(rows.all { it.accountLast4 == "3333" && it.bankName == "HSBC Bank" })

        val summary = parser.summary(hsbc)!!
        assertEquals("3333", summary.cardLast4)
        assertEquals(LocalDate.of(2027, 1, 25), summary.statementDate)
        assertEquals(money("2600.00"), summary.outstanding)
        assertEquals(money("52.00"), summary.minimumDue)
        assertEquals(LocalDate.of(2027, 1, 9), summary.dueDate)
        assertEquals(money("100000.00"), summary.creditLimit)
        assertEquals(money("97400.00"), summary.availableLimit)
    }

    @Test fun `a card owes its limit minus what is available, which counts unbilled EMI principal`() {
        // Total amount due 1,250.40, but 12,000 of EMI principal still to bill also counts against the limit.
        val summary = HdfcCreditCardPdfParser().summary(hdfc)!!
        assertEquals(money("1250.40"), summary.outstanding)
        assertEquals(money("10000"), summary.owed)
    }

    @Test fun `without a printed limit and available figure a card owes what the statement says`() {
        val bare = CardStatementSummary("Bank", "1111", LocalDate.of(2026, 10, 1), outstanding = money("500.00"))
        assertEquals(money("500.00"), bare.owed)
    }
}
