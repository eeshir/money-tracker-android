package com.moneytracker.app.data.rewards

import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import com.moneytracker.app.data.statement.RowRewardEntry
import com.moneytracker.app.data.statement.StatementDuplicates
import com.moneytracker.app.data.statement.StatementRewardEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * Randomised (seeded, so repeatable) checks of things that must hold for ANY input, to catch cases nobody thought of:
 * crashes, a transaction matched twice, caps exceeded, a duplicate that is also a keeper.
 */
class RewardInvariantsTest {

    private val merchants = listOf("Swiggy", "Blinkit", "Amazon", "Myntra", "Zomato", "Fuel Stop", "Joining Fee", "SmartBuy_Bonus_5per_CB0000", "Shop")
    private val base = LocalDate.of(2026, 3, 1)

    private fun randomTx(rnd: Random, id: Long, senders: List<String?>): TransactionEntity {
        val type = when (rnd.nextInt(10)) {
            0 -> TransactionType.INCOME
            1 -> TransactionType.EXPENSE
            else -> TransactionType.CREDIT
        }
        return TransactionEntity(
            id = id,
            amount = BigDecimal(rnd.nextInt(1, 30) * 50).add(BigDecimal(rnd.nextInt(0, 3)).divide(BigDecimal(2))),
            merchantName = merchants[rnd.nextInt(merchants.size)],
            category = "x",
            transactionType = type,
            dateTime = base.plusDays(rnd.nextLong(0, 120)).atTime(rnd.nextInt(0, 24), rnd.nextInt(0, 60)),
            bankName = "Bank", accountNumber = if (rnd.nextInt(8) == 0) "9999" else "1234",
            smsSender = senders[rnd.nextInt(senders.size)], transactionHash = "h$id",
            isDeleted = rnd.nextInt(15) == 0, currency = if (rnd.nextInt(12) == 0) "USD" else "INR"
        )
    }

    private fun randomProgram(rnd: Random) = CardRewardProgram(
        bankName = "Bank", cardLast4 = "1234", baseRatePercent = listOf("0", "0.25", "1", "1.5")[rnd.nextInt(4)],
        pointValue = if (rnd.nextBoolean()) "1" else null,
        rules = listOf(
            RewardRule(
                "10%", listOf("swiggy", "blinkit"), "10", monthlyCap = listOf(null, "100", "1200")[rnd.nextInt(3)],
                earlierMonthlyCap = "50", earlierUntil = base.plusDays(40).toString(),
                extraKeywords = listOf("zomato"), extraFrom = base.plusDays(60).toString(),
                exceptKeywords = listOf("amazon"), aboveCapPercent = "0", earlierAboveCapPercent = "1.5"
            )
        ),
        excludedKeywords = listOf("fuel"), noRewardForeignFrom = base.plusDays(70).toString()
    )

    @Test
    fun `the calculator never crashes and never pays a negative reward for a purchase`() {
        val rnd = Random(7)
        repeat(1500) { n ->
            val program = randomProgram(rnd)
            val txns = (1L..rnd.nextLong(0, 40)).map { randomTx(rnd, it, listOf(null, "SMS", "Bank CC PDF")) }
            val overrides = if (rnd.nextInt(5) == 0 && txns.isNotEmpty()) mapOf(txns.first().id to "3") else emptyMap()
            val rewards = RewardCalculator.compute(program, txns, cycleDay = rnd.nextInt(1, 31).takeIf { rnd.nextBoolean() }, overrides = overrides)
            rewards.forEach { r ->
                assertTrue("case $n: purchase ${r.transaction.id} paid ${r.reward}", r.refund || r.cashbackCredit || r.reward.signum() >= 0)
                assertTrue("case $n: a reward with scale > 2: ${r.reward}", r.reward.scale() <= 2)
            }
            assertTrue("case $n: a deleted or other-card row was counted", rewards.all { !it.transaction.isDeleted && it.transaction.accountNumber == "1234" })
        }
    }

    @Test
    fun `a rule's monthly cap is never exceeded when nothing is paid above it`() {
        val rnd = Random(11)
        repeat(800) { n ->
            val cap = BigDecimal(rnd.nextInt(1, 6) * 100)
            val program = CardRewardProgram(
                "Bank", "1234", baseRatePercent = "0",
                rules = listOf(RewardRule("r", listOf("swiggy"), "10", monthlyCap = cap.toPlainString(), aboveCapPercent = "0"))
            )
            val txns = (1L..rnd.nextLong(1, 30)).map { id ->
                randomTx(rnd, id, listOf("SMS")).copy(
                    merchantName = "Swiggy", transactionType = TransactionType.CREDIT, isDeleted = false, accountNumber = "1234", currency = "INR"
                )
            }
            val rewards = RewardCalculator.compute(program, txns, cycleDay = 17)
            rewards.groupBy { RewardCalculator.cycleOf(it.transaction.dateTime.toLocalDate(), 17) }.forEach { (cycle, rows) ->
                val sum = rows.fold(BigDecimal.ZERO) { a, r -> a + r.reward }
                assertTrue("case $n cycle $cycle: $sum over cap $cap", sum <= cap)
            }
        }
    }

    @Test
    fun `the reconciler matches each row and transaction at most once and never calls a matched purchase a duplicate`() {
        val rnd = Random(23)
        repeat(1500) { n ->
            val txns = (1L..rnd.nextLong(0, 30)).map { randomTx(rnd, it, listOf(null, "SMS")).copy(accountNumber = "1234", isDeleted = false) }
            val stmtDate = base.plusDays(rnd.nextLong(20, 100))
            val rows = (0 until rnd.nextInt(0, 25)).map {
                RowRewardEntry(
                    stmtDate.minusDays(rnd.nextLong(0, 30)).toEpochDay(),
                    (BigDecimal(rnd.nextInt(1, 30) * 50)).toPlainString(), rnd.nextInt(0, 90).toString(), rnd.nextInt(8) == 0
                )
            }
            val entry = StatementRewardEntry("Bank", "1234", stmtDate.toEpochDay(), earned = "0", rows = rows)
            val result = StatementReconciler.reconcile(txns, listOf(entry))

            assertEquals("case $n: rows were lost or invented", rows.size, result.rowFor.size + result.unmatchedRows.size)
            val dropIds = result.duplicates.map { it.drop.id }
            val keepIds = result.duplicates.map { it.keep.id }
            assertEquals("case $n: one transaction dropped twice", dropIds.size, dropIds.toSet().size)
            assertEquals("case $n: one transaction kept for two duplicates", keepIds.size, keepIds.toSet().size)
            assertTrue("case $n: a matched purchase was called a duplicate", dropIds.none { it in result.rowFor.keys })
            assertTrue("case $n: a duplicate was kept without being matched", keepIds.all { it in result.rowFor.keys })
            assertTrue("case $n: a purchase is both dropped and kept", dropIds.none { it in keepIds })
            result.rowFor.forEach { (id, row) ->
                val tx = txns.first { it.id == id }
                assertEquals(row.amount, tx.amount.stripTrailingZeros().toPlainString())
                assertEquals(row.credit, tx.transactionType == TransactionType.INCOME)
                assertTrue("case $n: matched rows more than a day apart", Math.abs(row.day - tx.dateTime.toLocalDate().toEpochDay()) <= 1)
            }
        }
    }

    @Test
    fun `the statement and SMS duplicate finder pairs a row once, keeps the SMS copy and drops the statement copy`() {
        val rnd = Random(31)
        repeat(1500) { n ->
            val txns = (1L..rnd.nextLong(0, 40)).map { randomTx(rnd, it, listOf(null, "SMS", "Bank CC PDF")) }
            val found = StatementDuplicates.find(txns)
            val all = found.flatMap { listOf(it.keep.id, it.drop.id) }
            assertEquals("case $n: a transaction is in two pairs", all.size, all.toSet().size)
            found.forEach { d ->
                assertTrue("case $n: dropped a non-statement row", d.drop.smsSender?.endsWith("CC PDF") == true)
                assertTrue("case $n: kept a statement row", d.keep.smsSender?.endsWith("CC PDF") != true)
                assertEquals(d.keep.amount.compareTo(d.drop.amount), 0)
                assertEquals(d.keep.accountNumber, d.drop.accountNumber)
                assertTrue("case $n: not within a day", Math.abs(d.keep.dateTime.toLocalDate().toEpochDay() - d.drop.dateTime.toLocalDate().toEpochDay()) <= 1)
                assertTrue("case $n: a deleted row was paired", !d.keep.isDeleted && !d.drop.isDeleted)
            }
        }
    }
}
