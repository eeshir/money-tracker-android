package com.pennywiseai.tracker.data.statement

import com.pennywiseai.parser.core.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** HDFC Bank account statement text as it extracts, with made-up names and figures. */
class HdfcBankPdfParserTest {

    private val parser = HdfcBankPdfParser()

    private val statement = """
        Date Narration Chq./Ref.No. Value Dt Withdrawal Amt. Deposit Amt. Closing Balance
        01/09/26 A2AINT01 -SAMPLE EMPLOYER PRIVATE L 0000001111111111 01/09/26 1,000.00 1,500.00
        IMITED -SALARY -SALARYFORAUG2026
        02/09/26 UPI-SAMPLE
        GROCER-sample.grocer@okbank
        0000222233334444 02/09/26 40.00 1,460.00
        BANK0000123-222233334444-UPI
        03/09/26 POS 512345XXXXXX0001 123456 03SEP26 10:1 0000000000123456 03/09/26 60.00 1,400.00
        Page No .: 1 Statement of account
        MR SAMPLE HOLDER
        Statement From : 01/09/2026 To : 30/09/2026
        Account No : 50100000009999 SAMPLE VARIANT
        HDFC BANK LIMITED
        *Closing balance includes funds earmarked for hold and uncleared funds
        Registered Office Address: HDFC Bank House, Mumbai
        5:00 MUMBAI SAMPLE ONLINE STORE
        10/09/26 IB BILLPAY DR-HDFC8E-512345XXXXXX0001 1700000000000001 10/09/26 300.00 1,100.00
        30/09/26 INTEREST PAID TILL 30-SEP-2026 000000000000000 30/09/26 3.00 1,103.00
        STATEMENT SUMMARY :-
        Opening Balance Dr Count Cr Count Debits Credits Closing Bal
        500.00 3 2 400.00 1,003.00 1,103.00
        Page No .: 2 Statement of account
        Registered Office Address: HDFC Bank House, Mumbai
    """.trimIndent()

    private fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate()

    @Test
    fun `recognises an HDFC account statement and not the card one`() {
        assertTrue(parser.canHandle(statement))
        assertFalse(parser.canHandle("HDFC Bank Credit Cards\nCredit Card Statement"))
        assertTrue(PdfParserFactory.getParser(statement) is HdfcBankPdfParser)
    }

    @Test
    fun `a payee paid through PhonePe doesn't make it a PhonePe statement`() {
        val viaApp = statement.replace("UPI-SAMPLE\nGROCER-sample.grocer@okbank", "UPI-SAMPLE\nGROCER-PHONEPE.sample@ybl")
        assertTrue(PdfParserFactory.getParser(viaApp) is HdfcBankPdfParser)
    }

    @Test
    fun `reads each row with its direction from the running balance`() {
        val rows = parser.parse(statement)
        assertEquals(listOf("1000.00", "40.00", "60.00", "300.00", "3.00"), rows.map { it.amount.toPlainString() })
        assertEquals(
            listOf(
                TransactionType.INCOME, TransactionType.EXPENSE, TransactionType.EXPENSE,
                TransactionType.EXPENSE, TransactionType.INCOME
            ),
            rows.map { it.type }
        )
        assertEquals(LocalDate.of(2026, 9, 2), dateOf(rows[1].timestamp))
        assertTrue(rows.all { it.accountLast4 == "9999" && it.bankName == "HDFC Bank" && it.sender == HDFC_PDF_SENDER })
    }

    @Test
    fun `a narration split by the page block joins back up`() {
        val pos = parser.parse(statement)[2]
        assertEquals("SAMPLE ONLINE STORE", pos.merchant)
        assertFalse("Registered Office" in pos.smsBody || "SAMPLE HOLDER" in pos.smsBody)
    }

    @Test
    fun `payees and references come from the narration and reference column`() {
        val rows = parser.parse(statement)
        // The bank wraps mid-word; the payee keeps the space the wrap left.
        assertEquals("SAMPLE EMPLOYER PRIVATE L IMITED", rows[0].merchant)
        assertEquals("SAMPLE GROCER", rows[1].merchant)
        assertEquals("222233334444", rows[1].reference)
        assertEquals("Credit card payment", rows[3].merchant)
        assertEquals("Interest", rows[4].merchant)
        assertNull(rows[4].reference)
    }

    @Test
    fun `the closing balance is the last row's, so the import can check it`() {
        val closing = StatementBalanceCheck.closingRow(parser.parse(statement))!!
        assertEquals(BigDecimal("1103.00"), closing.balance)
    }
}
