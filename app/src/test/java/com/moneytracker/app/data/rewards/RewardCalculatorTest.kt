package com.moneytracker.app.data.rewards

import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class RewardCalculatorTest {

    private fun tx(
        id: Long, amount: String, merchant: String, at: LocalDateTime = LocalDateTime.of(2026, 10, 5, 12, 0),
        type: TransactionType = TransactionType.CREDIT, last4: String = "1234"
    ) = TransactionEntity(
        id = id, amount = BigDecimal(amount), merchantName = merchant, category = "Others", transactionType = type,
        dateTime = at, bankName = "Bank A", accountNumber = last4, transactionHash = "h$id"
    )

    private val program = CardRewardProgram(
        bankName = "bank a", cardLast4 = "1234", baseRatePercent = "1",
        rules = listOf(RewardRule("Online shop", listOf("shopco"), "5", monthlyCap = "100")),
        excludedKeywords = listOf("fuel")
    )

    @Test
    fun `base rate applies when no rule matches`() {
        val r = RewardCalculator.compute(program, listOf(tx(1, "1000", "Corner Cafe"))).single()
        assertEquals(BigDecimal("10.00"), r.reward)
        assertNull(r.ruleLabel)
    }

    @Test
    fun `a matching rule beats the base rate, case-insensitively`() {
        val r = RewardCalculator.compute(program, listOf(tx(1, "1000", "SHOPCO India"))).single()
        assertEquals(BigDecimal("50.00"), r.reward)
        assertEquals("Online shop", r.ruleLabel)
    }

    @Test
    fun `monthly cap trims the reward and resets next month`() {
        val rewards = RewardCalculator.compute(
            program,
            listOf(
                tx(1, "1500", "ShopCo", LocalDateTime.of(2026, 10, 1, 9, 0)),
                tx(2, "1000", "ShopCo", LocalDateTime.of(2026, 10, 2, 9, 0)),
                tx(3, "1000", "ShopCo", LocalDateTime.of(2026, 11, 2, 9, 0))
            )
        )
        assertEquals(listOf("75.00", "25.00", "50.00"), rewards.map { it.reward.toPlainString() })
        assertEquals(listOf(false, true, false), rewards.map { it.capped })
    }

    @Test
    fun `cap usage counts only the current statement cycle`() {
        val rewards = RewardCalculator.compute(
            program,
            listOf(
                tx(1, "2000", "ShopCo", LocalDateTime.of(2026, 10, 10, 9, 0)), // previous cycle (to 17 Oct)
                tx(2, "400", "ShopCo", LocalDateTime.of(2026, 10, 20, 9, 0)),  // 20 reward
                tx(3, "100", "Corner Cafe", LocalDateTime.of(2026, 10, 21, 9, 0)) // base rate, no cap
            ),
            cycleDay = 17
        )
        val usage = RewardCalculator.capUsage(program, rewards, 17, java.time.LocalDate.of(2026, 11, 5)).single()
        assertEquals(BigDecimal("20.00"), usage.used)
        assertEquals(BigDecimal("100"), usage.limit)
        assertEquals(java.time.LocalDate.of(2026, 11, 17), usage.resets)
    }

    @Test
    fun `a cap follows the statement cycle, not the calendar month`() {
        // Statements dated the 17th: the 10th and the 20th of October are in different cycles.
        val rewards = RewardCalculator.compute(
            program,
            listOf(
                tx(1, "2000", "ShopCo", LocalDateTime.of(2026, 10, 10, 9, 0)),
                tx(2, "2000", "ShopCo", LocalDateTime.of(2026, 10, 20, 9, 0))
            ),
            cycleDay = 17
        )
        assertEquals(listOf("100.00", "100.00"), rewards.map { it.reward.toPlainString() })
        assertEquals(listOf(false, false), rewards.map { it.capped })
    }

    @Test
    fun `a rate set on one purchase beats the card rate, even for an excluded merchant`() {
        val rewards = RewardCalculator.compute(
            program,
            listOf(tx(1, "1000", "ShopCo"), tx(2, "1000", "ShopCo"), tx(3, "1000", "City Fuel Station")),
            overrides = mapOf(2L to "2", 3L to "1")
        )
        assertEquals(listOf("50.00", "20.00", "10.00"), rewards.map { it.reward.toPlainString() })
        assertEquals(listOf("Online shop", "Your rate", "Your rate"), rewards.map { it.ruleLabel })
    }

    @Test
    fun `a rule can have a lower cap before its terms changed`() {
        val rule = RewardRule("Shop", listOf("shopco"), "10", monthlyCap = "1200", earlierMonthlyCap = "1000", earlierUntil = "2026-07-26")
        val p = program.copy(rules = listOf(rule), baseRatePercent = "0")
        val old = RewardCalculator.compute(p, listOf(tx(1, "20000", "ShopCo", LocalDateTime.of(2026, 7, 10, 9, 0)))).single()
        val new = RewardCalculator.compute(p, listOf(tx(2, "20000", "ShopCo", LocalDateTime.of(2026, 8, 10, 9, 0)))).single()
        assertEquals(0, BigDecimal("1000").compareTo(old.reward))
        assertEquals(0, BigDecimal("1200").compareTo(new.reward))
    }

    @Test
    fun `a category added later only counts from its date, and exceptions never match`() {
        val rule = RewardRule(
            "10", listOf("food"), "10", extraKeywords = listOf("shopping"), extraFrom = "2026-07-26", exceptKeywords = listOf("myntra")
        )
        val p = program.copy(rules = listOf(rule), baseRatePercent = "1")
        fun reward(merchant: String, at: LocalDateTime) =
            RewardCalculator.compute(p, listOf(tx(1, "1000", merchant, at).copy(category = "Shopping"))).single().reward.toPlainString()
        assertEquals("10.00", reward("Boutique", LocalDateTime.of(2026, 7, 1, 9, 0)))   // before: base 1%
        assertEquals("100.00", reward("Boutique", LocalDateTime.of(2026, 8, 1, 9, 0)))  // after: shopping 10%
        assertEquals("10.00", reward("Myntra", LocalDateTime.of(2026, 8, 1, 9, 0)))     // excluded from the rule
    }

    @Test
    fun `spend past the cap earns the above-cap rate, which changed at the cut-off`() {
        val rule = RewardRule(
            "10", listOf("shopco"), "10", monthlyCap = "100", earlierMonthlyCap = "100", earlierUntil = "2026-07-26",
            aboveCapPercent = "0", earlierAboveCapPercent = "1.5"
        )
        val p = program.copy(rules = listOf(rule), baseRatePercent = "0")
        fun reward(at: LocalDateTime) = RewardCalculator.compute(p, listOf(tx(1, "2000", "ShopCo", at))).single().reward.toPlainString()
        // Rs 1,000 of the Rs 2,000 earns the capped Rs 100; the other Rs 1,000 earns 1.5% before, nothing after.
        assertEquals("115.00", reward(LocalDateTime.of(2026, 7, 1, 9, 0)))
        assertEquals("100.00", reward(LocalDateTime.of(2026, 8, 1, 9, 0)))
    }

    @Test
    fun `an exception is overridden by a longer keyword that contains it`() {
        val rule = RewardRule("g", listOf("grocer", "amazon fresh"), "10", exceptKeywords = listOf("amazon"))
        val p = program.copy(rules = listOf(rule), baseRatePercent = "1")
        fun rate(merchant: String) = RewardCalculator.compute(p, listOf(tx(1, "1000", merchant).copy(category = "Groceries"))).single().reward.toPlainString()
        assertEquals("100.00", rate("Amazon Fresh"))
        assertEquals("10.00", rate("Amazon Pay"))
    }

    @Test
    fun `foreign spends earn nothing from the cut-off date`() {
        val p = program.copy(noRewardForeignFrom = "2026-07-26")
        val before = RewardCalculator.compute(p, listOf(tx(1, "1000", "Abroad Shop", LocalDateTime.of(2026, 7, 1, 9, 0)).copy(currency = "USD"))).single()
        val after = RewardCalculator.compute(p, listOf(tx(2, "1000", "Abroad Shop", LocalDateTime.of(2026, 8, 1, 9, 0)).copy(currency = "USD"))).single()
        assertEquals("10.00", before.reward.toPlainString())
        assertTrue(after.excluded)
    }

    @Test
    fun `fees and taxes never earn a reward`() {
        val rewards = RewardCalculator.compute(
            program,
            listOf(tx(1, "999", "Joining Fee Cc2618"), tx(2, "179.82", "Igst Assessment @18.00% Cc2618"), tx(3, "500", "Coffee House"))
        )
        assertEquals(listOf(true, true, false), rewards.map { it.excluded })
    }

    @Test
    fun `a points card pays whole points, rounding each purchase down`() {
        val p = program.copy(baseRatePercent = "5", rules = emptyList(), pointValue = "1")
        val r = RewardCalculator.compute(p, listOf(tx(1, "299", "Amazon"))).single()
        assertEquals("14.00", r.reward.toPlainString()) // 5% of 299 is 14.95 -> 14 points
    }

    @Test
    fun `a SmartBuy bonus credit is the cashback itself, and an EMI conversion is not a refund`() {
        val rewards = RewardCalculator.compute(
            program,
            listOf(
                tx(1, "3560", "Myntra"),
                tx(2, "178", "SmartBuy_Bonus_5per_CB0000", type = TransactionType.INCOME),
                tx(3, "15355.88", "Amazon"),
                tx(4, "15355.88", "Aggregator Emi - Offus Credit", type = TransactionType.INCOME)
            )
        )
        val credit = rewards.single { it.transaction.id == 2L }
        assertTrue(credit.cashbackCredit)
        assertEquals("178.00", credit.reward.toPlainString())
        assertTrue(rewards.none { it.transaction.id == 4L }) // not treated as a refund taking the reward back
        assertEquals(3, rewards.size)
    }

    @Test
    fun `excluded spends earn nothing`() {
        val r = RewardCalculator.compute(program, listOf(tx(1, "2000", "City Fuel Station"))).single()
        assertTrue(r.excluded)
        assertEquals(BigDecimal.ZERO, r.reward)
    }

    @Test
    fun `a refund takes back the reward and frees the cap`() {
        val rewards = RewardCalculator.compute(
            program,
            listOf(
                tx(1, "2000", "ShopCo", LocalDateTime.of(2026, 10, 1, 9, 0)),
                tx(2, "1000", "ShopCo refund", LocalDateTime.of(2026, 10, 3, 9, 0), type = TransactionType.INCOME)
            )
        )
        assertEquals(listOf("100.00", "-50.00"), rewards.map { it.reward.toPlainString() })
        assertTrue(rewards[1].refund)
    }

    @Test
    fun `card bill payments and cashback credits are not refunds`() {
        val rewards = RewardCalculator.compute(
            program,
            listOf(
                tx(1, "5000", "CC PAYMENT received", type = TransactionType.INCOME),
                tx(2, "50", "Cashback credit", type = TransactionType.INCOME)
            )
        )
        assertTrue(rewards.isEmpty())
    }

    @Test
    fun `other cards, deleted rows and non-purchases are ignored`() {
        val rewards = RewardCalculator.compute(
            program,
            listOf(
                tx(1, "1000", "Cafe", last4 = "9999"),
                tx(3, "1000", "Cafe").copy(isDeleted = true)
            )
        )
        assertTrue(rewards.isEmpty())
    }

    @Test
    fun `a manual refund linked to a purchase takes back that purchase's reward and frees its cap room`() {
        val purchase = tx(1, "2000", "ShopCo India")
        val manualRefund = TransactionEntity(
            id = 2, amount = BigDecimal("1000"), merchantName = "Ajio", category = "Income",
            transactionType = TransactionType.INCOME, dateTime = LocalDateTime.of(2026, 10, 6, 12, 0),
            bankName = "Manual Entry", accountNumber = null, transactionHash = "h2"
        )
        val links = mapOf(2L to listOf(1L to BigDecimal("1000")))

        val rewards = RewardCalculator.compute(program, listOf(purchase, manualRefund), refundLinks = links)

        // 2,000 at 5% is capped at 100; refunding half takes back half of what it earned, under the same rule.
        assertEquals(BigDecimal("100.00"), rewards[0].reward)
        assertEquals(BigDecimal("-50.00"), rewards[1].reward)
        assertEquals("Online shop", rewards[1].ruleLabel)
        val used = RewardCalculator.capUsage(program, rewards, null, today = LocalDateTime.of(2026, 10, 7, 12, 0).toLocalDate())
        assertEquals(BigDecimal("50.00"), used.single().used)
    }

    @Test
    fun `a refund with no link to this card is still ignored`() {
        val manualRefund = TransactionEntity(
            id = 2, amount = BigDecimal("1000"), merchantName = "Ajio", category = "Income",
            transactionType = TransactionType.INCOME, dateTime = LocalDateTime.of(2026, 10, 6, 12, 0),
            bankName = "Manual Entry", accountNumber = null, transactionHash = "h2"
        )
        assertTrue(RewardCalculator.compute(program, listOf(tx(1, "2000", "ShopCo India"), manualRefund)).size == 1)
    }
}
