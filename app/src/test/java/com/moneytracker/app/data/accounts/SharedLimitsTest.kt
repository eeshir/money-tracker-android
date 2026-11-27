package com.moneytracker.app.data.accounts

import com.moneytracker.app.data.database.entity.AccountBalanceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class SharedLimitsTest {
    private fun card(bank: String, last4: String, owed: String, limit: String) = AccountBalanceEntity(
        bankName = bank, accountLast4 = last4, balance = BigDecimal(owed), creditLimit = BigDecimal(limit),
        timestamp = LocalDateTime.of(2026, 6, 1, 12, 0), isCreditCard = true, currency = "INR"
    )

    private val cards = listOf(
        card("Bank A", "1111", "20000", "50000"), card("Bank A", "2222", "15000", "50000"),
        card("Bank B", "3333", "1000", "10000")
    )

    private fun assertMoney(expected: String, actual: BigDecimal) = assertEquals(0, BigDecimal(expected).compareTo(actual))

    @Test fun `a shared limit is counted once, less what the cards owe together`() {
        val pool = SharedLimits.poolOf(cards[0], cards, setOf("bank a"))!!
        assertMoney("15000", pool.available)
        assertEquals(70, pool.usedPercent)
        assertMoney("24000", SharedLimits.totalAvailable(cards, setOf("bank a")))
    }

    @Test fun `equal limits are not shared unless the person says so, and one card has no pool`() {
        assertNull(SharedLimits.poolOf(cards[0], cards, emptySet()))
        assertMoney("74000", SharedLimits.totalAvailable(cards, emptySet()))
        assertNull(SharedLimits.poolOf(cards[2], cards, setOf("bank b")))
    }

    @Test fun `the largest recorded limit is the shared one`() {
        val stale = listOf(card("Bank A", "1111", "0", "90000"), card("Bank A", "2222", "0", "240000"))
        assertMoney("240000", SharedLimits.totalAvailable(stale, setOf("bank a")))
    }
}
