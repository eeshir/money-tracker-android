package com.moneytracker.app.data.rewards

import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import com.moneytracker.app.data.statement.RowRewardEntry
import com.moneytracker.app.data.statement.StatementDuplicate
import com.moneytracker.app.data.statement.StatementRewardEntry
import java.time.LocalDate

/**
 * Lines up one card's transactions with the rows its statements printed (ICICI prints a reward on each row).
 *
 * 1. A row matches a transaction of the same day, amount and direction.
 * 2. A row left over matches a still-unmatched transaction of the same amount one day away: a purchase's SMS and its
 *    statement row can be dated a day apart (it posted late).
 * 3. A purchase still unmatched, well inside a statement's cycle, that has the same amount as a row already taken by a
 *    different transaction one day or less away, is a duplicate of that transaction (the same purchase announced twice).
 *    Each transaction can be the twin of one duplicate only, and a purchase in the last day of a cycle is never called a
 *    duplicate, since its own row may sit on a statement that is not on file.
 */
object StatementReconciler {

    class Result(
        /** Transaction id -> the statement row it matched. */
        val rowFor: Map<Long, RowRewardEntry>,
        /** Statement rows no transaction matched. */
        val unmatchedRows: List<RowRewardEntry>,
        val duplicates: List<StatementDuplicate>
    )

    private class Slot(val row: RowRewardEntry) {
        var taker: TransactionEntity? = null
    }

    fun reconcile(transactions: List<TransactionEntity>, entries: List<StatementRewardEntry>): Result {
        val slots = entries.flatMap { e -> e.rows.map { Slot(it) } }
        val ordered = transactions
            .filter { !it.isDeleted && (it.transactionType == TransactionType.CREDIT || it.transactionType == TransactionType.INCOME) }
            .sortedWith(compareBy({ it.dateTime }, { it.id }))
        if (slots.isEmpty()) return Result(emptyMap(), emptyList(), emptyList())

        fun TransactionEntity.day() = dateTime.toLocalDate().toEpochDay()
        fun TransactionEntity.credit() = transactionType == TransactionType.INCOME
        fun TransactionEntity.amountText() = amount.stripTrailingZeros().toPlainString()
        fun Slot.same(tx: TransactionEntity) = row.amount == tx.amountText() && row.credit == tx.credit()

        val matched = HashMap<Long, Slot>()
        for (tx in ordered) {
            slots.firstOrNull { it.taker == null && it.same(tx) && it.row.day == tx.day() }?.let { it.taker = tx; matched[tx.id] = it }
        }
        for (tx in ordered) {
            if (tx.id in matched) continue
            slots.filter { it.taker == null && it.same(tx) && Math.abs(it.row.day - tx.day()) == 1L }
                .minByOrNull { it.row.day }
                ?.let { it.taker = tx; matched[tx.id] = it }
        }

        // A cycle is the month ending on the statement date; the last day is left out (see above).
        val cycles = entries.map { e ->
            val end = e.statementDay
            LocalDate.ofEpochDay(end).minusMonths(1).plusDays(1).toEpochDay() to end - 1
        }
        val usedTwins = HashSet<Long>()
        val duplicates = ArrayList<StatementDuplicate>()
        for (tx in ordered) {
            if (tx.id in matched || tx.transactionType != TransactionType.CREDIT) continue
            if (cycles.none { (start, lastSafe) -> tx.day() in start..lastSafe }) continue
            val twin = slots.firstOrNull { s ->
                val taker = s.taker
                taker != null && taker.id != tx.id && taker.id !in usedTwins && taker.transactionType == TransactionType.CREDIT &&
                    s.same(tx) && Math.abs(taker.day() - tx.day()) <= 1
            }?.taker ?: continue
            usedTwins += twin.id
            duplicates += StatementDuplicate(keep = twin, drop = tx)
        }

        return Result(
            rowFor = matched.mapValues { it.value.row },
            unmatchedRows = slots.filter { it.taker == null }.map { it.row },
            duplicates = duplicates
        )
    }
}
