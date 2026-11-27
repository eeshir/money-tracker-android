package com.moneytracker.app.data.statement

import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import com.moneytracker.app.data.rewards.CardRewardPresets
import com.moneytracker.app.data.rewards.RewardCalculator
import com.moneytracker.app.data.rewards.StatementBonus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class EmiConversionsTest {
    private fun tx(id: Long, amount: String, merchant: String, type: TransactionType, month: Int, day: Int) = TransactionEntity(
        id = id, amount = BigDecimal(amount), merchantName = merchant, category = "x", transactionType = type,
        dateTime = LocalDateTime.of(2026, month, day, 12, 0), bankName = "HDFC Bank", accountNumber = "1234",
        transactionHash = "h$id"
    )

    private val purchase = tx(1, "12000", "Shop", TransactionType.CREDIT, 6, 18)
    private val conversion = tx(2, "12000.00", "Aggregator Emi - Offus Credit", TransactionType.INCOME, 6, 22)
    private val principal = tx(3, "1000", "Offus Emi,prin Nb:01", TransactionType.CREDIT, 7, 17)
    private val refund = tx(4, "150", "Shop", TransactionType.INCOME, 8, 21)
    private val prepayment = tx(5, "150", "Offus Emi Refund Pre Payment Ln1", TransactionType.CREDIT, 8, 24)

    private fun ids(vararg t: TransactionEntity) = EmiConversions.toExclude(t.toList()).map { it.id }.sorted()

    @Test fun `the purchase and its conversion credit are left out and the instalments count`() {
        assertEquals(listOf(1L, 2L), ids(purchase, conversion, principal))
    }

    @Test fun `a refund put against the loan is left out with the charge that used it`() {
        assertEquals(listOf(4L, 5L), ids(refund, prepayment, principal))
    }

    @Test fun `a conversion credit waits for its purchase, and a row put back stays back`() {
        assertTrue(ids(conversion, principal).isEmpty())
        assertTrue(ids(purchase.copy(amount = BigDecimal("11999")), conversion).isEmpty())
        // The person put the purchase back into totals after it was handled: nothing is excluded again.
        assertTrue(ids(purchase, conversion.copy(excludedFromAnalytics = true)).isEmpty())
    }

    @Test fun `the bought item still earns its reward, and a loan refund takes none back`() {
        val program = CardRewardPresets.suggestionsFor("HDFC Bank").first { it.name == "MoneyBack+" }.copy(cardLast4 = "1234")
        val bought = purchase.copy(merchantName = "Amazon", excludedFromAnalytics = true)
        val rewards = RewardCalculator.compute(
            program, listOf(bought, refund.copy(excludedFromAnalytics = true), prepayment),
            refundLinks = mapOf(4L to listOf(1L to BigDecimal("150")))
        )
        assertTrue(rewards.first { it.transaction.id == 1L }.reward.signum() > 0)
        assertTrue(rewards.none { it.reward.signum() < 0 })
    }

    @Test fun `a Tata Neu statement's coins beyond its rows are a statement bonus`() {
        val program = CardRewardPresets.suggestionsFor("HDFC Bank").first { it.name == "Tata Neu Plus" }
        val day = LocalDate.of(2026, 11, 17).toEpochDay()
        val rows = listOf(RowRewardEntry(day, "100", "20", credit = false), RowRewardEntry(day, "200", "100", credit = false))
        val entry = StatementRewardEntry("HDFC Bank", "1234", day, earned = "135", isPoints = true, rows = rows)
        val bonus = StatementBonus.of(program, listOf(entry)).single()
        assertEquals(0, bonus.amount.compareTo(BigDecimal("15")))
        assertEquals(LocalDate.of(2026, 11, 17), bonus.date)
        assertTrue(StatementBonus.of(program, listOf(entry.copy(earned = "120"))).isEmpty())
    }
}
