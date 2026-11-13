package com.pennywiseai.tracker.data.statement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class StatementBalanceCheckTest {

    private fun target(
        statement: String,
        statementLimit: String?,
        current: String?,
        currentLimit: String?,
        reportsAvailable: Boolean,
        changeAfter: String = "0"
    ) = StatementBalanceCheck.cardTarget(
        statementOutstanding = BigDecimal(statement),
        statementLimit = statementLimit?.let(::BigDecimal),
        currentBalance = current?.let(::BigDecimal),
        currentLimit = currentLimit?.let(::BigDecimal),
        reportsAvailable = reportsAvailable,
        changeAfterStatement = BigDecimal(changeAfter)
    )

    @Test
    fun `a card whose SMS state the available limit keeps that figure and fixes only the limit`() {
        // Stored limit 101,000 was a guess; 6,000 owed means 95,000 available.
        // The statement's real limit 100,000 leaves 5,000 owed, whatever payments went unseen.
        assertEquals(BigDecimal("5000"), target("500", "100000", "6000", "101000", reportsAvailable = true))
    }

    @Test
    fun `applying the same statement twice changes nothing the second time`() {
        val first = target("500", "100000", "6000", "101000", reportsAvailable = true)!!
        // The corrected card: limit now 100,000, owed 5,000.
        assertNull(target("500", "100000", first.toPlainString(), "100000", reportsAvailable = true))
    }

    @Test
    fun `a card with no limit in its SMS is the statement plus what happened since`() {
        // Owed 1,000 at the statement; 400 spent and 150 refunded after it.
        assertEquals(BigDecimal("1250"), target("1000", "50000", "3000", null, reportsAvailable = false, changeAfter = "250"))
    }

    @Test
    fun `that rule is also the same however many times it is applied`() {
        val first = target("1000", "50000", "3000", null, reportsAvailable = false, changeAfter = "250")!!
        assertNull(target("1000", "50000", first.toPlainString(), "50000", reportsAvailable = false, changeAfter = "250"))
    }

    @Test
    fun `never negative`() {
        assertEquals(BigDecimal.ZERO, target("0", "50000", "500", null, reportsAvailable = false, changeAfter = "-900"))
    }

    @Test
    fun `a card with no stored balance gets no correction`() {
        assertNull(target("1000", "50000", null, "50000", reportsAvailable = true))
    }

    @Test
    fun `statement figures that look misread are not trusted`() {
        assertTrue(StatementBalanceCheck.isPlausible(BigDecimal("500"), BigDecimal("150000")))
        assertTrue(StatementBalanceCheck.isPlausible(BigDecimal("52000"), BigDecimal("50000"))) // a little over limit
        assertFalse(StatementBalanceCheck.isPlausible(BigDecimal("-1"), BigDecimal("150000")))
        assertFalse(StatementBalanceCheck.isPlausible(BigDecimal("600000"), BigDecimal("150000")))
        assertTrue(StatementBalanceCheck.isPlausible(BigDecimal("900000"), null)) // no limit to compare with
    }
}
