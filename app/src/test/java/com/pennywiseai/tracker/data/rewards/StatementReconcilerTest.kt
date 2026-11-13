package com.pennywiseai.tracker.data.rewards

import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.data.statement.RowRewardEntry
import com.pennywiseai.tracker.data.statement.StatementDuplicates
import com.pennywiseai.tracker.data.statement.StatementRewardEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class StatementReconcilerTest {

    private val statementDate = LocalDate.of(2026, 5, 5)

    private fun day(d: Int, month: Int = 4) = LocalDate.of(2026, month, d)

    private fun tx(id: Long, d: LocalDate, amount: String, type: TransactionType = TransactionType.CREDIT, merchant: String = "Shop", hour: Int = 9) =
        TransactionEntity(
            id = id, amount = BigDecimal(amount), merchantName = merchant, category = "x", transactionType = type,
            dateTime = d.atTime(hour, 0), bankName = "Bank", accountNumber = "1234", transactionHash = "h$id"
        )

    private fun row(d: LocalDate, amount: String, points: String = "1", credit: Boolean = false) =
        RowRewardEntry(d.toEpochDay(), amount, points, credit)

    private fun statement(vararg rows: RowRewardEntry, on: LocalDate = statementDate) =
        StatementRewardEntry("Bank", "1234", on.toEpochDay(), earned = "0", rows = rows.toList())

    @Test
    fun `rows match transactions of the same day and amount, once each`() {
        val r = StatementReconciler.reconcile(
            listOf(tx(1, day(10), "100"), tx(2, day(10), "100")), listOf(statement(row(day(10), "100", "7")))
        )
        assertEquals(setOf(1L), r.rowFor.keys) // the second identical purchase has no row left
        assertEquals("7", r.rowFor.getValue(1L).points)
        assertTrue(r.unmatchedRows.isEmpty())
    }

    @Test
    fun `a purchase that posted a day late still matches its row`() {
        val r = StatementReconciler.reconcile(listOf(tx(1, day(11), "250")), listOf(statement(row(day(10), "250", "5"))))
        assertEquals("5", r.rowFor.getValue(1L).points)
        assertTrue(r.duplicates.isEmpty())
    }

    @Test
    fun `the same purchase announced twice a day apart is a duplicate, and the matched one is kept`() {
        // The real pattern: the statement prints one row, the app has the purchase on two consecutive days.
        val first = tx(1, day(25), "365", merchant = "Swiggy")
        val extra = tx(2, day(26), "365", merchant = "Swiggy")
        val r = StatementReconciler.reconcile(listOf(first, extra), listOf(statement(row(day(25), "365", "3"))))
        assertEquals(listOf(1L to 2L), r.duplicates.map { it.keep.id to it.drop.id })
        assertEquals(setOf(1L), r.rowFor.keys)
    }

    @Test
    fun `three alerts for two printed purchases leave exactly one duplicate`() {
        val rows = arrayOf(row(day(26), "400"), row(day(29), "400"))
        val r = StatementReconciler.reconcile(
            listOf(tx(1, day(26), "400"), tx(2, day(29), "400"), tx(3, day(30), "400")), listOf(statement(*rows))
        )
        assertEquals(listOf(3L), r.duplicates.map { it.drop.id })
    }

    @Test
    fun `genuine different purchases are never duplicates`() {
        val r = StatementReconciler.reconcile(
            listOf(tx(1, day(25), "365"), tx(2, day(26), "366"), tx(3, day(27), "365")),
            listOf(statement(row(day(25), "365"), row(day(26), "366"), row(day(27), "365")))
        )
        assertTrue(r.duplicates.isEmpty())
    }

    @Test
    fun `a twin can explain only one duplicate`() {
        val r = StatementReconciler.reconcile(
            listOf(tx(1, day(25), "365"), tx(2, day(24), "365"), tx(3, day(26), "365")), listOf(statement(row(day(25), "365")))
        )
        assertEquals(1, r.duplicates.size)
    }

    @Test
    fun `a purchase on the last day of the cycle is not called a duplicate`() {
        // Its own row may sit on the next statement, which may not be on file.
        val r = StatementReconciler.reconcile(
            listOf(tx(1, day(4, 5), "365"), tx(2, day(5, 5), "365")), listOf(statement(row(day(4, 5), "365")))
        )
        assertTrue(r.duplicates.isEmpty())
    }

    @Test
    fun `outside any statement cycle nothing is judged`() {
        val r = StatementReconciler.reconcile(
            listOf(tx(1, LocalDate.of(2026, 9, 9), "365"), tx(2, LocalDate.of(2026, 9, 10), "365")), listOf(statement(row(day(25), "365")))
        )
        assertTrue(r.duplicates.isEmpty())
    }

    @Test
    fun `refunds match refund rows and never purchase rows`() {
        val r = StatementReconciler.reconcile(
            listOf(tx(1, day(10), "100", TransactionType.INCOME)),
            listOf(statement(row(day(10), "100", "3"), row(day(10), "100", "-3", credit = true)))
        )
        assertEquals("-3", r.rowFor.getValue(1L).points)
    }

    @Test
    fun `deleted rows and other types are ignored`() {
        val r = StatementReconciler.reconcile(
            listOf(tx(1, day(10), "100").copy(isDeleted = true), tx(2, day(10), "100", TransactionType.EXPENSE)),
            listOf(statement(row(day(10), "100")))
        )
        assertTrue(r.rowFor.isEmpty())
        assertEquals(1, r.unmatchedRows.size)
    }

    @Test
    fun `a card with no printed rows is left alone`() {
        val noRows = StatementRewardEntry("Bank", "1234", statementDate.toEpochDay(), earned = "5")
        val found = StatementDuplicates.findAdjacent(listOf(tx(1, day(25), "365"), tx(2, day(26), "365")), listOf(noRows))
        assertTrue(found.isEmpty())
    }

    @Test
    fun `findAdjacent only looks at the card the statement belongs to`() {
        val other = tx(3, day(26), "365").copy(accountNumber = "9999")
        val found = StatementDuplicates.findAdjacent(
            listOf(tx(1, day(25), "365"), tx(2, day(26), "365"), other), listOf(statement(row(day(25), "365")))
        )
        assertEquals(listOf(2L), found.map { it.drop.id })
    }

    @Test
    fun `the reward of a late-posting purchase comes from its printed row`() {
        val program = CardRewardProgram("Bank", "1234", baseRatePercent = "1", pointValue = "1")
        val txns = listOf(tx(1, day(11), "1000"))
        val reported = ReportedRewards.of(program, listOf(statement(row(day(10), "1000", "25"))), txns)
        val reward = RewardCalculator.compute(program, txns, reported = reported).single()
        assertEquals("25.00", reward.reward.toPlainString())
    }
}
