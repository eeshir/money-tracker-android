package com.pennywiseai.tracker.data.statement

import com.pennywiseai.parser.core.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Against a page of a real ICICI savings statement, redacted by the reporter (#874). */
class IciciBankPdfParserTest {

    private val parser = IciciBankPdfParser()

    // The sample starts at the table; a real statement carries the bank name above it.
    private val statement = "ICICI Bank Limited\nStatement of Transactions\n" +
        javaClass.getResource("/statements/icici_savings_sample.txt")!!.readText()

    private val parsed by lazy { parser.parse(statement) }

    private fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate()

    @Test
    fun `recognises ICICI statements only`() {
        assertTrue(parser.canHandle(statement))
        assertFalse(parser.canHandle("Google Pay transaction statement"))
    }

    @Test
    fun `every row is found with its amount and date`() {
        assertEquals(23, parsed.size)
        assertEquals(BigDecimal("100.00"), parsed.first().amount)
        assertEquals(LocalDate.of(2025, 10, 17), dateOf(parsed.first().timestamp))
        assertEquals(BigDecimal("10.00"), parsed.last().amount)
        assertEquals(LocalDate.of(2025, 10, 25), dateOf(parsed.last().timestamp))
    }

    @Test
    fun `direction comes from the running balance`() {
        // 1,24,118.55 -> 1,24,718.55: the FD interest row is a deposit.
        val interest = parsed.first { it.amount == BigDecimal("600.00") }
        assertEquals(TransactionType.INCOME, interest.type)
        assertEquals("Interest", interest.merchant)
        // 1,25,000.00 -> 1,24,950.00: a withdrawal.
        assertEquals(TransactionType.EXPENSE, parsed[1].type)
        assertEquals(20, parsed.count { it.type == TransactionType.EXPENSE })
        assertEquals(3, parsed.count { it.type == TransactionType.INCOME })
    }

    @Test
    fun `merchant comes from the UPI, card and VSI particulars`() {
        assertEquals("MERCHANT 13", parsed[0].merchant)   // VIN/ before the date
        assertEquals("MERCHANT 01", parsed[1].merchant)   // UPI/
        assertEquals("MERCHANT 07", parsed[7].merchant)   // VSI/ after the date
        assertEquals("MERCHANT 11", parsed[11].merchant)  // payee split over two lines
        assertEquals("MERCHANT 16", parsed[21].merchant)
    }

    @Test
    fun `rows carry the bank and the source text`() {
        assertTrue(parsed.all { it.bankName == "ICICI Bank" && it.currency == "INR" })
        assertTrue(parsed[1].smsBody.contains("UPI/MERCHANT 01"))
        assertFalse(parsed[1].smsBody.contains("MERCHANT 02"))
    }

    @Test
    fun `an opening balance settles the first row's direction`() {
        val withOpening = statement.replace("VIN/MERCHANT 13\n17-10-2025", "B/F 1,25,100.00\nVIN/MERCHANT 13\n17-10-2025")
        assertEquals(TransactionType.EXPENSE, parser.parse(withOpening).first().type)
    }

    @Test
    fun `identical rows on different dates get different duplicate hashes`() {
        // Same FD-interest particulars and amount, a month apart.
        val text = """
            ICICI Bank Limited
            DATE
            PARTICULARS
            DEPOSITS
            WITHDRAWALS
            BALANCE
            Int on FD/RD XXX1406 Tds:0.Int:600 and
            25-10-2025
            600.00
            1,00,600.00
            Int on FD/RD XXX1406 Tds:0.Int:600 and
            25-11-2025
            600.00
            1,01,200.00
        """.trimIndent()
        val rows = parser.parse(text)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.type == TransactionType.INCOME })
        assertTrue(rows[0].generateTransactionId() != rows[1].generateTransactionId())
    }

    @Test
    fun `inline layout - date opens the row and amount balance closes it`() {
        // Synthetic, in the layout newer statements extract to: particulars may share
        // the date's line, and the row closes on the first "amount balance" line.
        val text = """
            ICICI Bank
            DATE MODE PARTICULARS DEPOSITS WITHDRAWALS BALANCE
            01-09-2026  B/F  10,000.00
            01-09-2026  BIL/INFT/TEST0001/CC BillPay-1111/Self 1,500.00 8,500.00
            02-09-2026
            SAMPLE PAYEE
            UPI/SAMPLE PAYEE/payee@bank/Pay/SAMPLE BANK/111122223333/ABCDEF
            12
            20.00 8,480.00
            03-09-2026
            SAMPLE EMPLOYER
            NEFT-TEST0002-SAMPLE EMPLOYER
            1,000.00  9,480.00
            Total: 1,000.00 1,520.00 9,480.00
        """.trimIndent()
        val rows = parser.parse(text)
        assertEquals(3, rows.size)
        assertEquals(listOf("1500.00", "20.00", "1000.00"), rows.map { it.amount.toPlainString() })
        assertEquals(listOf(TransactionType.EXPENSE, TransactionType.EXPENSE, TransactionType.INCOME), rows.map { it.type })
        assertEquals(BigDecimal("9480.00"), rows.last().balance)
        assertEquals("SAMPLE PAYEE", rows[1].merchant)
        assertEquals(LocalDate.of(2026, 9, 2), dateOf(rows[1].timestamp))
    }
}
