package com.pennywiseai.tracker.data.statement

import com.pennywiseai.parser.core.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** IDBI Bank statement text as it extracts, with made-up names and figures. */
class IdbiBankPdfParserTest {

    private val parser = IdbiBankPdfParser()

    private val statement = """
        Customer Details Branch Details
        Transaction Date From 01-Sep-2026 to 30-Sep-2026 Statement downloaded on: 01/10/2026 10:00:00
        Account No : 1234567890009999
        Branch IFSC Code : IBKL0001234
        STATEMENT OF ACCOUNT :1234567890009999
        S.No Txn Date Value Date Description Cheque No Withdrawals
        (Dr)
        Deposits
        (Cr)
        Balance
        (INR)
        1 28/09/2026 18:30:00 28/09/2026 UPI/111122223333/Sample
        Grocery Store
        40.00 1460.00
        2 26/09/2026 12:00:00 26/09/2026 Int.:01-07-2026 To 25-09-2026 5.00 1500.00
        IDBI Bank Ltd. Regd. Office: Sample Tower, Mumbai. Website: www.idbi.com
        Page 1 of 2
        3 20/09/2026 09:15:00 20/09/2026 NEFT-ABCD12345678901-
        SAMPLE EMPLOYER LLP
        1000.00 1495.00
        4 05/09/2026 21:00:00 05/09/2026 UPI/444455556666/Sample Cafe 5.00 495.00
        Dr Count Cr Count Debits Credits
        2 2 45.0 1005.0
        Legends for transactions in your account statement
    """.trimIndent()

    private fun timeOf(millis: Long): LocalDateTime = Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Kolkata")).toLocalDateTime()

    @Test
    fun `recognises an IDBI statement and nothing else`() {
        assertTrue(parser.canHandle(statement))
        assertFalse(parser.canHandle("ICICI Bank\nDATE MODE PARTICULARS DEPOSITS WITHDRAWALS BALANCE"))
        assertTrue(PdfParserFactory.getParser(statement) is IdbiBankPdfParser)
    }

    @Test
    fun `reads rows oldest first with direction from the running balance`() {
        val rows = parser.parse(statement)
        assertEquals(listOf("5.00", "1000.00", "5.00", "40.00"), rows.map { it.amount.toPlainString() })
        // The oldest row is settled against the opening the totals imply (500.00).
        assertEquals(
            listOf(TransactionType.EXPENSE, TransactionType.INCOME, TransactionType.INCOME, TransactionType.EXPENSE),
            rows.map { it.type }
        )
        assertEquals(BigDecimal("1460.00"), rows.last().balance)
    }

    @Test
    fun `wrapped descriptions join up and give the payee and reference`() {
        val rows = parser.parse(statement)
        val grocery = rows.last()
        assertEquals("Sample Grocery Store", grocery.merchant)
        assertEquals("111122223333", grocery.reference)
        assertEquals("SAMPLE EMPLOYER LLP", rows[1].merchant)
        assertEquals("ABCD12345678901", rows[1].reference)
        assertEquals("Interest", rows[2].merchant)
        assertNull(rows[2].reference)
    }

    @Test
    fun `keeps the transaction time and lands on the SMS account`() {
        val rows = parser.parse(statement)
        assertEquals(LocalDateTime.of(2026, 9, 28, 18, 30), timeOf(rows.last().timestamp))
        assertTrue(rows.all { it.accountLast4 == "9999" && it.bankName == "IDBI Bank" && it.sender == IDBI_PDF_SENDER })
    }

    @Test
    fun `the closing balance is the newest row's, so the import can check it`() {
        val closing = StatementBalanceCheck.closingRow(parser.parse(statement))!!
        assertEquals(BigDecimal("1460.00"), closing.balance)
    }

    @Test
    fun `a branch entry is named as one rather than by its code`() {
        val branch = statement.replace(
            "4 05/09/2026 21:00:00 05/09/2026 UPI/444455556666/Sample Cafe 5.00 495.00",
            "4 05/09/2026 21:00:00 05/09/2026 BO012345/05-09-26 21:00:00\n/000000123456\n5.00 495.00"
        )
        assertEquals("Branch transaction", parser.parse(branch).first().merchant)
    }

    @Test
    fun `page footers inside the table are not read as description`() {
        assertTrue(parser.parse(statement).none { "Regd. Office" in it.smsBody || "Page 1" in it.smsBody })
    }
}
