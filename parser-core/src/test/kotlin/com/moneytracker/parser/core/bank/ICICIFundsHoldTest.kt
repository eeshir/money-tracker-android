package com.moneytracker.parser.core.bank

import com.moneytracker.parser.core.TransactionType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Funds held for a UPI mandate move the available balance but are not spending. */
class ICICIFundsHoldTest {

    private val parser = ICICIBankParser()
    private val blocked = "Dear Customer, your account has been blocked by an amount of INR 9000.00, against the UPI one-time mandate-ICICI Bank."
    private val unblocked = "Dear Customer, your account has been unblocked by an amount of INR 7500.00, against the UPI one-time mandate-ICICI Bank."
    private val day1 = 1_793_000_000_000L
    private val day2 = day1 + 2 * 24 * 60 * 60 * 1000L

    @Test
    fun `a block lowers the account and a release raises it`() {
        val hold = parser.parse(blocked, "VM-ICICIT-S", day1)!!
        assertEquals(BigDecimal("9000.00"), hold.amount)
        assertEquals(TransactionType.EXPENSE, hold.type)
        assertEquals("Funds blocked (UPI mandate)", hold.merchant)
        assertNull(hold.accountLast4)
        assertEquals("ICICI Bank", hold.bankName)

        val release = parser.parse(unblocked, "VA-ICICIT-S", day1)!!
        assertEquals(BigDecimal("7500.00"), release.amount)
        assertEquals(TransactionType.INCOME, release.type)
        assertEquals("Funds released (UPI mandate)", release.merchant)
    }

    @Test
    fun `the same text on another day is another hold, and on the same day from another clock is the same one`() {
        val first = parser.parse(blocked, "VM-ICICIT-S", day1)!!
        val laterDay = parser.parse(blocked, "VM-ICICIT-S", day2)!!
        val sameDayLaterClock = parser.parse(blocked, "VM-ICICIT-S", day1 + 60_000)!!
        assertNotEquals(first.transactionHash, laterDay.transactionHash)
        assertEquals(first.transactionHash, sameDayLaterClock.transactionHash)
        assertNotNull(first.transactionHash)
    }

    @Test
    fun `other mandate messages do not count as holds`() {
        for (sms in listOf(
            "Dear Customer, you have approved the mandate of INR 9000.00 raised by SAMPLE CO LI on 28-Oct-26, through RRN 123456789012-ICICI Bank.",
            "Your AutoPay mandate with ONETIME is successfully created towards SAMPLE CO LI from 28-Oct-26 to 16-Nov-26 for Rs 9000.00, RRN 123456789012-ICICI Bank.",
            "Dear Customer, the mandate of INR 7500.00 raised by SAMPLE CO L on 27-Oct-26 and is revoked through RRN 123456789012-ICICI Bank."
        )) {
            assertFalse(FundsHold.isHold(sms))
            assertNull(parser.parse(sms, "AX-ICICIT-S", day1), sms)
        }
        assertTrue(FundsHold.isHold(blocked))
    }
}
