package com.moneytracker.app.data.statement

import com.moneytracker.parser.core.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** SBI e-statement text as it extracts, with made-up names and figures. */
class SbiAccountPdfParserTest {

    private val parser = SbiAccountPdfParser()

    private val statement = """
        Balance
        Rs.60.7
        XXXXXXX9999Customer
        As on 30-09-26
        TRANSACTION DETAILS
        SAVING ACCOUNT
        XXXXXXX1234
        Nominee Registered No
        Available Balance 440.00
        SBIN0001234IFSC Code
        Date Transaction Reference Ref.No./Chq.No. Credit Debit Balance
        null null null null null null
        03-09-26 UPI/DR/111122223333/Sample Payee /BANK/sample@bank/UPI - 0 40.00 460.00
        12-09-26 UPI/DR/444455556666/SAMPLE/BANK/sample/UPI - 0 25.00 435.00
        25-09-26 INTEREST CREDIT - 5.00 0 440.00
        *All dates are in DD-MM-YY format
        TRANSACTION OVERVIEW
        Your Closing Balance on 30-09-26: 440.00
        Your Opening Balance on 01-09-26: 500.00
    """.trimIndent()

    private fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate()

    @Test
    fun `recognises an SBI account statement and nothing else`() {
        assertTrue(parser.canHandle(statement))
        assertFalse(parser.canHandle("ICICI Bank\nDATE MODE PARTICULARS DEPOSITS WITHDRAWALS BALANCE"))
        assertTrue(PdfParserFactory.getParser(statement) is SbiAccountPdfParser)
    }

    @Test
    fun `reads each row with its direction, payee, reference and running balance`() {
        val rows = parser.parse(statement)
        assertEquals(3, rows.size)
        assertEquals(listOf(TransactionType.EXPENSE, TransactionType.EXPENSE, TransactionType.INCOME), rows.map { it.type })
        assertEquals(listOf("40.00", "25.00", "5.00"), rows.map { it.amount.toPlainString() })
        assertEquals("Sample Payee", rows[0].merchant)
        assertEquals("111122223333", rows[0].reference)
        assertEquals("Interest", rows[2].merchant)
        assertEquals(BigDecimal("440.00"), rows[2].balance)
        assertEquals(LocalDate.of(2026, 9, 25), dateOf(rows[2].timestamp))
        assertTrue(rows.all { it.accountLast4 == "1234" && it.bankName == "State Bank of India" })
    }

    @Test
    fun `the closing balance is the last row's, so the import can check it`() {
        val closing = StatementBalanceCheck.closingRow(parser.parse(statement))!!
        assertEquals(BigDecimal("440.00"), closing.balance)
    }

    @Test
    fun `two identical debits on one day stay distinct because their balances differ`() {
        val twice = statement.replace(
            "12-09-26 UPI/DR/444455556666/SAMPLE/BANK/sample/UPI - 0 25.00 435.00",
            "12-09-26 UPI/DR/444455556666/SAMPLE/BANK/sample/UPI - 0 10.00 425.00\n" +
                "12-09-26 UPI/DR/444455556666/SAMPLE/BANK/sample/UPI - 0 10.00 415.00"
        )
        val rows = parser.parse(twice).filter { it.amount == BigDecimal("10.00") }
        assertEquals(2, rows.size)
        assertTrue(rows[0].generateTransactionId() != rows[1].generateTransactionId())
    }
}
