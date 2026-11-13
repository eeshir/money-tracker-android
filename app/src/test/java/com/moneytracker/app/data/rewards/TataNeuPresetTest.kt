package com.moneytracker.app.data.rewards

import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class TataNeuPresetTest {

    private val program = CardRewardPresets.suggestionsFor("HDFC Bank").first { it.name == "Tata Neu Plus" }
        .copy(cardLast4 = "5678")

    private fun tx(id: Long, amount: String, merchant: String) = TransactionEntity(
        id = id, amount = BigDecimal(amount), merchantName = merchant, category = "Others",
        transactionType = TransactionType.CREDIT, dateTime = LocalDateTime.of(2026, 11, 3, 12, 0),
        bankName = "HDFC Bank", accountNumber = "5678", transactionHash = "h$id"
    )

    private fun reward(t: TransactionEntity) = RewardCalculator.compute(program, listOf(t)).single()

    @Test
    fun `a UPI payment to a merchant id earns the 1 percent base, in whole coins`() {
        val r = reward(tx(1, "517.00", "*ab1cd@xyz"))
        assertEquals(BigDecimal("5.00"), r.reward)
        assertEquals(null, r.ruleLabel)
    }

    @Test
    fun `a Tata partner earns 2 percent`() {
        val r = reward(tx(2, "1000.00", "BigBasket"))
        assertEquals(BigDecimal("20.00"), r.reward)
        assertEquals("Tata Neu partners", r.ruleLabel)
    }

    @Test
    fun `words inside other words do not zero a reward`() {
        for (name in listOf("Premium Dairy", "Current Store", "Taxi Ride")) {
            assertTrue(name, reward(tx(3, "1000.00", name)).reward.signum() > 0)
        }
    }

    @Test
    fun `fuel and insurance earn nothing`() {
        assertTrue(reward(tx(4, "1000.00", "Fuel Station")).excluded)
        assertTrue(reward(tx(5, "1000.00", "Life Insurance Premium")).excluded)
    }

    @Test
    fun `the card's saved variant keeps its own point value`() {
        val saved = program.copy(pointValue = null)
        assertEquals("1", CardRewardPresets.withDefaults(saved).pointValue)
    }
}
