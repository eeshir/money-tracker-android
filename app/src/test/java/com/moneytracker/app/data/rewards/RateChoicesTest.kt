package com.moneytracker.app.data.rewards

import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class RateChoicesTest {

    private fun tx(merchant: String, type: TransactionType = TransactionType.CREDIT, at: LocalDateTime = LocalDateTime.of(2026, 10, 5, 12, 0)) =
        TransactionEntity(
            id = 1, amount = BigDecimal("1000"), merchantName = merchant, category = "Others", transactionType = type,
            dateTime = at, bankName = "Bank A", accountNumber = "1234", transactionHash = "h1"
        )

    private val amazon = CardRewardPresets.suggestionsFor("ICICI Bank").first()
    private val tata = CardRewardPresets.suggestionsFor("HDFC Bank").first { it.name == "Tata Neu Plus" }
    private val hsbc = CardRewardPresets.suggestionsFor("HSBC Bank").first()

    @Test
    fun `an Amazon purchase can be switched between 5 and 2 percent`() {
        assertEquals(listOf("5", "2"), RateChoices.forPurchase(amazon, tx("Amazon"))!!.rates.map { it.toPlainString() })
        assertNull(RateChoices.forPurchase(amazon, tx("Corner Cafe")))
        assertNull(RateChoices.forPurchase(amazon, tx("Amazon", TransactionType.INCOME)))
    }

    @Test
    fun `a Tata partner purchase can be switched between 2 and 7 percent`() {
        assertEquals(listOf("2", "7"), RateChoices.forPurchase(tata, tx("Croma Store"))!!.rates.map { it.toPlainString() })
        assertNull(RateChoices.forPurchase(tata, tx("Corner Cafe")))
        assertNull(RateChoices.forPurchase(hsbc, tx("Amazon")))
    }

    @Test
    fun `a statement figure rounded to points still shows its choice`() {
        val choice = RateChoices.forPurchase(amazon, tx("Amazon"))!!
        assertEquals(BigDecimal("2"), RateChoices.selected(choice, BigDecimal("1.97")))
        assertNull(RateChoices.selected(choice, BigDecimal("1.00")))
    }

    @Test
    fun `on a statement purchase the Tata switch only adds the Neu app part, so the bonus is not counted twice`() {
        val program = tata.copy(bankName = "Bank A", cardLast4 = "1234")
        val buy = tx("Croma Store")
        val day = buy.dateTime.toLocalDate()
        val statement = com.moneytracker.app.data.statement.StatementRewardEntry(
            bankName = "Bank A", cardLast4 = "1234", statementDay = day.plusDays(10).toEpochDay(), earned = "20", isPoints = true,
            rows = listOf(com.moneytracker.app.data.statement.RowRewardEntry(day.toEpochDay(), "1000", "10", false, "CROMA", day.plusDays(10).toEpochDay()))
        )
        fun total(rate: String?): String {
            val overrides = rate?.let { mapOf(1L to it) }.orEmpty()
            val rows = RewardCalculator.compute(program, listOf(buy), null, overrides, ReportedRewards.of(program, listOf(statement), listOf(buy)))
            val bonus = StatementBonus.of(program, listOf(statement)).fold(BigDecimal.ZERO) { a, b -> a + b.amount }
            return (rows.single().reward + bonus).stripTrailingZeros().toPlainString()
        }
        assertEquals("20", total(null)) // 10 on the row + 10 listed apart
        assertEquals("20", total("2"))
        assertEquals("70", total("7")) // plus the app's 50
    }

    @Test
    fun `Myntra earns the HSBC offer rate only inside the offer dates`() {
        fun rate(at: LocalDateTime) = RewardCalculator.compute(
            hsbc.copy(cardLast4 = "1234", bankName = "Bank A"), listOf(tx("Myntra", at = at))
        ).single().ratePercent.stripTrailingZeros().toPlainString()
        assertEquals("1.5", rate(LocalDateTime.of(2026, 7, 30, 12, 0)))
        assertEquals("10", rate(LocalDateTime.of(2026, 7, 31, 0, 30)))
        assertEquals("10", rate(LocalDateTime.of(2026, 10, 31, 23, 30)))
        assertEquals("1.5", rate(LocalDateTime.of(2026, 11, 1, 0, 30)))
    }

    @Test
    fun `the offer reaches rules saved before it`() {
        val old = hsbc.copy(rules = hsbc.rules.map { it.copy(offerKeywords = emptyList(), offerFrom = null, offerUntil = null) })
        assertEquals(hsbc, RewardRuleUpgrades.apply(old))
    }

    @Test
    fun `refunds come off before the cap, whatever the order`() {
        val program = CardRewardProgram(
            "Bank A", "1234", baseRatePercent = "0", rules = listOf(RewardRule("Shop", listOf("shopco"), "10", monthlyCap = "100"))
        )
        fun row(id: Long, amount: String, day: Int, type: TransactionType = TransactionType.CREDIT) =
            tx("ShopCo", type, LocalDateTime.of(2026, 10, day, 12, 0)).copy(id = id, amount = BigDecimal(amount), transactionHash = "h$id")
        fun total(vararg rows: TransactionEntity) =
            RewardCalculator.compute(program, rows.toList()).fold(BigDecimal.ZERO) { a, r -> a + r.reward }.toPlainString()
        // 3,000 bought and 1,500 returned: 1,500 still bought, which is over the cap.
        assertEquals("100.00", total(row(1, "3000", 1), row(2, "1500", 3, TransactionType.INCOME)))
        assertEquals("100.00", total(row(3, "400", 1, TransactionType.INCOME), row(1, "3000", 2), row(2, "500", 3, TransactionType.INCOME)))
        // Returned down to 800: under the cap again.
        assertEquals("80.00", total(row(1, "3000", 1), row(2, "2200", 3, TransactionType.INCOME)))
        val linked = RewardCalculator.compute(
            program, listOf(row(1, "3000", 1), row(2, "2200", 3, TransactionType.INCOME)),
            refundLinks = mapOf(2L to listOf(1L to BigDecimal("2200")))
        )
        assertEquals("80.00", linked.fold(BigDecimal.ZERO) { a, r -> a + r.reward }.toPlainString())
    }
}
