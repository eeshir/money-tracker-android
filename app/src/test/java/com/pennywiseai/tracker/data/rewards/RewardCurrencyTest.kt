package com.pennywiseai.tracker.data.rewards

import com.pennywiseai.tracker.data.database.entity.CardEntity
import com.pennywiseai.tracker.data.database.entity.CardType
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.data.statement.RowRewardEntry
import com.pennywiseai.tracker.data.statement.StatementRewardEntry
import com.pennywiseai.tracker.presentation.rewards.CardRewardsItem
import com.pennywiseai.tracker.presentation.rewards.byMerchant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/** Rewards in different currencies are never added together (a card can have foreign-currency purchases). */
class RewardCurrencyTest {

    private val program = CardRewardProgram("Bank", "1234", baseRatePercent = "1")

    private fun tx(id: Long, amount: String, currency: String, merchant: String = "Shop", day: Int = 10) = TransactionEntity(
        id = id, amount = BigDecimal(amount), merchantName = merchant, category = "x", transactionType = TransactionType.CREDIT,
        dateTime = LocalDateTime.of(2026, 5, day, 9, 0), bankName = "Bank", accountNumber = "1234", transactionHash = "h$id",
        currency = currency
    )

    private val card = CardEntity(cardLast4 = "1234", cardType = CardType.CREDIT, bankName = "Bank")

    @Test
    fun `a card's total is kept per currency, never one added figure`() {
        val rewards = RewardCalculator.compute(program, listOf(tx(1, "1000", "INR"), tx(2, "100", "USD")))
        val totals = CardRewardsItem(card, program, rewards).totals
        assertEquals(setOf("INR", "USD"), totals.keys)
        assertEquals(0, BigDecimal("10").compareTo(totals.getValue("INR").amount))
        assertEquals(0, BigDecimal("1").compareTo(totals.getValue("USD").amount))
    }

    @Test
    fun `the statement check only compares rewards in the card's own currency`() {
        val rewards = RewardCalculator.compute(program, listOf(tx(1, "1000", "INR"), tx(2, "100", "USD")))
        val entry = StatementRewardEntry("Bank", "1234", LocalDate.of(2026, 5, 25).toEpochDay(), earned = "10", isPoints = false)
        val check = StatementChecker.check(program, rewards, listOf(entry), currency = "INR").single()
        assertEquals(0, BigDecimal("10").compareTo(check.estimate)) // the 1 USD of reward is not added to 10 INR
        assertEquals(1, check.counted.size)
    }

    @Test
    fun `the same merchant in two currencies is two lines in the breakdown`() {
        val rewards = RewardCalculator.compute(program, listOf(tx(1, "1000", "INR", "Airline"), tx(2, "100", "USD", "Airline")))
        val lines = rewards.byMerchant()
        assertEquals(2, lines.size)
        assertEquals(setOf("INR", "USD"), lines.map { it.currency }.toSet())
    }

    @Test
    fun `only a purchase a statement printed is marked as coming from a statement`() {
        val p = program.copy(pointValue = "1")
        val printedDay = LocalDate.of(2026, 5, 10)
        val entry = StatementRewardEntry(
            "Bank", "1234", LocalDate.of(2026, 5, 25).toEpochDay(), earned = "5",
            rows = listOf(RowRewardEntry(printedDay.toEpochDay(), "1000", "5", false, statement = LocalDate.of(2026, 5, 25).toEpochDay()))
        )
        val txns = listOf(tx(1, "1000", "INR", day = 10), tx(2, "400", "INR", day = 12))
        val rewards = RewardCalculator.compute(p, txns, reported = ReportedRewards.of(p, listOf(entry), txns))
        assertNotNull(rewards.first { it.transaction.id == 1L }.statementDay)
        assertNull(rewards.first { it.transaction.id == 2L }.statementDay)
    }
}
