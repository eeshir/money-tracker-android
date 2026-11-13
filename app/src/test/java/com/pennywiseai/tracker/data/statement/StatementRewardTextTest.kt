package com.pennywiseai.tracker.data.statement

import com.pennywiseai.tracker.data.rewards.CardRewardProgram
import com.pennywiseai.tracker.data.rewards.StatementChecker
import com.pennywiseai.tracker.data.rewards.TransactionReward
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class StatementRewardTextTest {

    @Test
    fun `reads points earned from a sentence`() {
        val text = "Summary\nReward Points Earned this cycle: 1,250\nOther"
        assertEquals("1250" to true, StatementRewardText.earned(text))
    }

    @Test
    fun `reads cashback earned as an amount, not points`() {
        assertEquals("312.50" to false, StatementRewardText.earned("Cashback earned   Rs 312.50"))
    }

    @Test
    fun `reads the earned column of an opening-earned-redeemed-closing table`() {
        val text = "Opening Balance Earned Redeemed Closing Balance\n1,000 250 0 1,250"
        assertEquals("250" to true, StatementRewardText.earned(text))
    }

    @Test
    fun `a table whose row does not line up is not guessed`() {
        assertNull(StatementRewardText.earned("Opening Balance Earned Redeemed Closing Balance\n1,000 250"))
    }

    @Test
    fun `keeps the lines that mention rewards for diagnosis`() {
        val lines = StatementRewardText.relevantLines("Total due 500.00\nReward points summary\nPayment received")
        assertEquals(listOf("Reward points summary", "Payment received"), lines)
    }

    private fun reward(day: Int, amount: String) = TransactionReward(
        TransactionEntity(
            amount = BigDecimal("1000"), merchantName = "Shop", category = "x", transactionType = TransactionType.CREDIT,
            dateTime = LocalDateTime.of(2026, 10, day, 10, 0), transactionHash = "h$day"
        ),
        BigDecimal(amount), BigDecimal.ONE, null, excluded = false, capped = false
    )

    @Test
    fun `compares a statement with the estimate for its cycle and flags a real gap`() {
        val program = CardRewardProgram("Bank", "1234", pointValue = "0.25")
        val rewards = listOf(reward(5, "100"), reward(20, "100"), reward(28, "999"))
        val entry = StatementRewardEntry("Bank", "1234", LocalDate.of(2026, 10, 25).toEpochDay(), earned = "1000")
        val check = StatementChecker.check(program, rewards, listOf(entry)).single()
        assertEquals(BigDecimal("250.00"), check.reported)
        assertEquals(BigDecimal("200"), check.estimate) // the 28th is after the statement date
        assertTrue(check.mismatch)
    }

    @Test
    fun `a small difference is not a mismatch and points without a value are not valued`() {
        val rewards = listOf(reward(5, "100"))
        val close = StatementChecker.check(
            CardRewardProgram("Bank", "1234"), rewards,
            listOf(StatementRewardEntry("Bank", "1234", LocalDate.of(2026, 10, 25).toEpochDay(), earned = "104", isPoints = false))
        ).single()
        assertFalse(close.mismatch)
        val unvalued = StatementChecker.check(
            CardRewardProgram("Bank", "1234"), rewards,
            listOf(StatementRewardEntry("Bank", "1234", LocalDate.of(2026, 10, 25).toEpochDay(), earned = "400"))
        ).single()
        assertNull(unvalued.reported)
        assertEquals(BigDecimal("400"), unvalued.reportedPoints)
    }
}

class StatementRewardTextLayoutsTest {

    @Test
    fun `the bonus summary under the points table is already in its earned, so it is not added`() {
        val text = """
            Opening Balance Earned Disbursed Adjusted/Lapsed
            100 30 0 0
            Rewards Program Points Summary
            SR NO. PROGRAMS BONUS POINTS
            1 10X Cashpoints on Select Merchants 200 pts
            2 Reward Points on Grocery 50 pts
            Cash Back Summary
            3 ignored 999 pts
        """.trimIndent()
        assertEquals("30" to true, StatementRewardText.earned(text))
    }

    @Test
    fun `sums the cashback credit rows of a cashback card`() {
        val text = "05AUG CASHBACK CREDIT 1,200.50 CR\n06AUG SOME SHOP 300.00\n09AUG CASHBACK CREDIT 10.00 CR"
        assertEquals("1210.50", StatementRewardText.cashbackCredits(text))
        assertNull(StatementRewardText.cashbackCredits("06AUG SOME SHOP 300.00"))
    }

    @Test
    fun `a credit shown in a statement is checked against the cycle before it`() {
        val program = CardRewardProgram("Bank", "1234")
        val entry = StatementRewardEntry("Bank", "1234", LocalDate.of(2026, 10, 25).toEpochDay(), earned = "100", isPoints = false, forPreviousCycle = true)
        val tx = TransactionEntity(
            amount = BigDecimal("1000"), merchantName = "Shop", category = "x", transactionType = TransactionType.CREDIT,
            dateTime = LocalDateTime.of(2026, 9, 10, 10, 0), transactionHash = "h"
        )
        val rewards = listOf(TransactionReward(tx, BigDecimal("100"), BigDecimal.TEN, null, excluded = false, capped = false))
        assertEquals(BigDecimal("100"), StatementChecker.check(program, rewards, listOf(entry)).single().estimate)
    }
}

class IciciRowRewardsTest {
    @Test
    fun `sums the reward column of the rows, taking credits off`() {
        val text = """
            04/10/2026 11111111111 SOME SHOP 50 1,000.00
            05/10/2026 22222222222 OTHER SHOP 12 283.00
            06/10/2026 33333333333 REFUND SHOP -10 200.00 CR
        """.trimIndent()
        assertEquals(java.math.BigDecimal("52"), IciciCreditCardPdfParser().rowRewards(text))
    }
}

class PrintedRowRewardsTest {

    @Test
    fun `reads each ICICI row's printed reward, negative for a credit`() {
        val text = """
            04/10/2026 11111111111 SOME SHOP 50 1,000.00
            06/10/2026 33333333333 REFUND SHOP 10 200.00 CR
        """.trimIndent()
        val rows = IciciCreditCardPdfParser().rowRewardDetails(text)
        assertEquals(listOf(50, -10), rows.map { it.points.toInt() })
        // A credit is negative whether the statement prints its reward with a minus sign or not.
        val unsigned = IciciCreditCardPdfParser().rowRewardDetails("06/10/2026 33333333333 REFUND SHOP 10 200.00 CR")
        assertEquals(listOf(-10), unsigned.map { it.points.toInt() })
        assertEquals(listOf(false, true), rows.map { it.credit })
        assertEquals(java.time.LocalDate.of(2026, 10, 4), rows[0].date)
    }

    @Test
    fun `a printed reward replaces the rule's, once per matching purchase`() {
        val program = CardRewardProgram("Bank", "1234", baseRatePercent = "5", pointValue = "1")
        val day = java.time.LocalDate.of(2026, 10, 4)
        val entry = StatementRewardEntry(
            "Bank", "1234", day.plusDays(1).toEpochDay(), earned = "20",
            rows = listOf(RowRewardEntry(day.toEpochDay(), "1000", "20", false))
        )
        fun tx(id: Long) = TransactionEntity(
            id = id, amount = BigDecimal("1000"), merchantName = "Shop", category = "x", transactionType = TransactionType.CREDIT,
            dateTime = day.atTime(10, 0), bankName = "Bank", accountNumber = "1234", transactionHash = "h$id"
        )
        val rewards = com.pennywiseai.tracker.data.rewards.RewardCalculator.compute(
            program, listOf(tx(1), tx(2)),
            reported = com.pennywiseai.tracker.data.rewards.ReportedRewards.of(program, listOf(entry), listOf(tx(1), tx(2)))
        )
        // The first matching purchase takes the statement's 20; the second has no row left and uses the 5% rule.
        assertEquals(listOf("20.00", "50.00"), rewards.map { it.reward.toPlainString() })
        assertEquals(listOf("Statement", null), rewards.map { it.ruleLabel })
    }
}

class HdfcRewardTableTest {

    @Test
    fun `the table's earned already includes the bonus summary, so it is not added twice`() {
        val text = """
            Opening Balance Earned Disbursed Adjusted/Lapsed
            8 1,613 0 0
            Rewards Program Points Summary
            SR NO. PROGRAMS BONUS POINTS
            1 10X Cashpoints on Select Merchants 1600 pts
            2 MISSED 10X CASHPOINTS_KEY MERCHANTS 1 pts
            3 Reward Points_on_Grocery 12 pts
            Total 1613 pts
        """.trimIndent()
        assertEquals("1613" to true, StatementRewardText.earned(text))
    }
}
