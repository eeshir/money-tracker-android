package com.moneytracker.app.data.rewards

import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

class SmartBuyTest {
    private val program = CardRewardPresets.suggestionsFor("HDFC Bank").first { it.name == "MoneyBack+" }.copy(cardLast4 = "1234")

    private fun tx(
        id: Long, amount: String, merchant: String, day: Int, type: TransactionType = TransactionType.CREDIT,
        sms: String? = null, month: Int = 11
    ) = TransactionEntity(
        id = id, amount = BigDecimal(amount), merchantName = merchant, category = "Others", transactionType = type,
        dateTime = LocalDateTime.of(2026, month, day, 12, 0), transactionHash = "h$id", bankName = "HDFC Bank",
        accountNumber = "1234", smsBody = sms
    )

    private fun credit(id: Long, amount: String, day: Int) =
        tx(id, amount, "SmartBuy_Bonus_5per_CB000$id", day, TransactionType.INCOME)

    private fun rewards(vararg t: TransactionEntity) = RewardCalculator.compute(program, t.toList()).associateBy { it.transaction.id }

    private fun assertMoney(expected: String, actual: BigDecimal) =
        assertTrue("expected $expected, got $actual", BigDecimal(expected).compareTo(actual) == 0)

    @Test fun `a cashback voucher earns 5 percent and no CashPoints`() {
        val r = rewards(
            tx(1, "1000", "Gyftrsm10000001", 1),
            tx(2, "250", "My Blinkit voucher", 3, sms = "Spent Rs.250 On HDFC Bank Card 1234 At GYFTRSM10000002")
        )
        assertMoney("50", r[1]!!.reward)
        assertMoney("12.50", r[2]!!.reward)
        assertEquals("SmartBuy 5%", r[1]!!.ruleLabel)
    }

    @Test fun `a partner shop earns 5 percent and the card's 2 points per full 200`() {
        // 5% cashback, plus 2 points of 25 paise for each of the 20 full Rs 200.
        val r = rewards(tx(1, "4000", "Myntra", 2, sms = "Spent Rs.4000 On HDFC Bank Card 1234 At MYNTRA VIA SMARTBUY"))[1]!!
        assertMoney("200", r.cashbackPart)
        assertMoney("210", r.reward)
    }

    @Test fun `the discount route expects nothing later, and an unproven gyftr code assumes no rate`() {
        val r = rewards(tx(1, "850", "Gyftrvi10000003", 1), tx(2, "500", "Gyftrxx1", 2))
        assertMoney("0", r[1]!!.reward)
        assertEquals("SmartBuy instant discount, no cashback", r[1]!!.ruleLabel)
        assertMoney("0", r[2]!!.reward)
        assertEquals("SmartBuy: rate not known yet", r[2]!!.ruleLabel)
    }

    @Test fun `a credit that does arrive settles even a discount or unknown purchase`() {
        val r = rewards(tx(1, "1000", "Gyftrvi10000003", 1), credit(2, "50.00", 4))
        assertEquals(listOf(1L), r.keys.toList())
        assertMoney("50", r[1]!!.reward)
    }

    @Test fun `the bank's credit confirms the purchase and is not counted again`() {
        val r = rewards(tx(1, "1000", "Gyftrsm10000001", 1), credit(2, "50.00", 4))
        assertEquals(listOf(1L), r.keys.toList())
        assertMoney("50", r[1]!!.reward)
        assertEquals("SmartBuy 5%, paid by bank", r[1]!!.ruleLabel)
    }

    @Test fun `a credit pays the cashback purchase before a discount one, and one with no purchase still counts`() {
        val r = rewards(
            tx(1, "1000", "Gyftrsm10000001", 1),
            tx(2, "1000", "Gyftrvi10000003", 2),
            credit(3, "50.00", 5),
            credit(4, "77.00", 6)
        )
        assertEquals("SmartBuy 5%, paid by bank", r[1]!!.ruleLabel)
        assertMoney("0", r[2]!!.reward)
        assertTrue(r[4]!!.cashbackCredit)
    }

    @Test fun `cashback stops at 1000 a calendar month and starts again on the 1st`() {
        val computed = RewardCalculator.compute(
            program,
            listOf(
                tx(1, "18000", "Gyftrsm1", 5, month = 10), tx(2, "4000", "Gyftrsm2", 20, month = 10),
                tx(3, "1000", "Gyftrsm3", 1, month = 11)
            ),
            cycleDay = 17
        ).associateBy { it.transaction.id }
        assertMoney("900", computed[1]!!.reward)
        assertMoney("100", computed[2]!!.reward)
        assertTrue(computed[2]!!.capped)
        assertMoney("50", computed[3]!!.reward)
        val cap = RewardCalculator.capUsage(program, computed.values.toList(), 17, LocalDate.of(2026, 10, 25))
            .first { it.label == SmartBuy.CAP_LABEL }
        assertMoney("1000", cap.used)
        assertEquals(LocalDate.of(2026, 10, 31), cap.resets)
    }

    @Test fun `the Myntra portal code is a partner purchase`() {
        val r = rewards(tx(1, "7300", "Wwwmyntr1000004", 1))[1]!!
        assertMoney("365", r.cashbackPart)
        assertMoney("383", r.reward) // plus 36 full 200s x 2 points x Rs 0.25
    }

    @Test fun `both HDFC caps run by calendar month and show even when nothing is used`() {
        val caps = RewardCalculator.capUsage(program, emptyList(), 17, LocalDate.of(2026, 10, 25))
        assertEquals(listOf(SmartBuy.CAP_LABEL, "10X partners"), caps.map { it.label })
        assertTrue(caps.all { it.calendarMonth && it.resets == LocalDate.of(2026, 10, 31) })
        // Two Amazon buys either side of the 17th, same month: one cap of Rs 625 between them.
        val r = RewardCalculator.compute(
            program, listOf(tx(1, "20000", "Amazon", 10, month = 10), tx(2, "20000", "Amazon", 20, month = 10)), cycleDay = 17
        )
        assertMoney("625", r.fold(BigDecimal.ZERO) { a, x -> a + x.reward })
    }

    @Test fun `a direct Myntra purchase keeps the card own rate`() {
        assertTrue(rewards(tx(1, "7300", "Myntra Designs Private", 1))[1]!!.reward < BigDecimal("30"))
    }

    @Test fun `a rate the person set wins and is still not counted twice`() {
        val r = RewardCalculator.compute(
            program, listOf(tx(1, "1000", "Gyftrsm10000001", 1), credit(2, "50.00", 4)), overrides = mapOf(1L to "5")
        )
        assertEquals(1, r.size)
        assertMoney("50", r.single().reward)
    }

    @Test fun `a purchase the person set to 5 percent is not paid a second time by the bank credit`() {
        val r = RewardCalculator.compute(
            program, listOf(tx(1, "7300", "Some Shop", 1), credit(2, "365.00", 4)), overrides = mapOf(1L to "5")
        )
        assertEquals(listOf(1L), r.map { it.transaction.id })
    }

    @Test fun `saved rules gain Ajio once, keeping the person's own settings`() {
        val saved = CardRewardPresets.suggestionsFor("HSBC Bank").first().let { p ->
            p.copy(rules = p.rules.map { it.copy(extraKeywords = it.extraKeywords - RewardRuleUpgrades.HSBC_SHOPPING.toSet(), ratePercent = "9") })
        }
        val upgraded = RewardRuleUpgrades.apply(saved)
        assertTrue(upgraded.rules.first().extraKeywords.containsAll(RewardRuleUpgrades.HSBC_SHOPPING))
        assertEquals("9", upgraded.rules.first().ratePercent)
        assertEquals(upgraded, RewardRuleUpgrades.apply(upgraded))
    }

    @Test fun `an Ajio purchase billed by Reliance Retail earns the shopping rate on HSBC and counts toward its cap`() {
        val hsbc = CardRewardPresets.suggestionsFor("HSBC Bank").first().copy(cardLast4 = "1234")
        val buy = tx(1, "2000", "Reliance Retail Ltd", 27).copy(bankName = "HSBC Bank")
        val computed = RewardCalculator.compute(hsbc, listOf(buy), cycleDay = 25)
        assertMoney("200", computed.single().reward)
        val cap = RewardCalculator.capUsage(hsbc, computed, 25, LocalDate.of(2026, 11, 27)).single()
        assertMoney("200", cap.used)
        assertMoney("1000", cap.left)
    }
}

class MoneyBackBlocksTest {
    private val program = CardRewardPresets.suggestionsFor("HDFC Bank").first { it.name == "MoneyBack+" }.copy(cardLast4 = "1234")

    private fun reward(amount: String, merchant: String): BigDecimal = RewardCalculator.compute(
        program,
        listOf(
            TransactionEntity(
                id = 1, amount = BigDecimal(amount), merchantName = merchant, category = "Others",
                transactionType = TransactionType.CREDIT, dateTime = LocalDateTime.of(2026, 7, 20, 12, 0),
                transactionHash = "h", bankName = "HDFC Bank", accountNumber = "1234"
            )
        )
    ).single().reward

    private fun assertMoney(expected: String, actual: BigDecimal) =
        assertTrue("expected $expected, got $actual", BigDecimal(expected).compareTo(actual) == 0)

    @Test fun `points come only for each full 200`() {
        assertMoney("0", reward("90", "Swiggy"))
        // One full block: 20 points of 25 paise.
        assertMoney("5", reward("250", "Swiggy"))
        assertMoney("0", reward("199", "Some Shop"))
        assertMoney("0.50", reward("399", "Some Shop"))
    }

    @Test fun `saved MoneyBack rules gain Swiggy and the 10X cap once`() {
        val saved = program.copy(rules = listOf(RewardRule("10X partners", listOf("amazon"), "2.5")))
        val upgraded = RewardRuleUpgrades.apply(saved)
        assertEquals(listOf("amazon", "swiggy"), upgraded.rules.single().keywords)
        assertEquals("625", upgraded.rules.single().monthlyCap)
        assertEquals(upgraded, RewardRuleUpgrades.apply(upgraded))
    }
}
