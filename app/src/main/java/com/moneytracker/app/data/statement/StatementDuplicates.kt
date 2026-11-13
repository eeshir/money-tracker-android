package com.moneytracker.app.data.statement

import com.moneytracker.app.data.database.entity.TransactionEntity

/**
 * A card purchase that is in the app twice: once read from an SMS and once from the card's statement.
 * That happens when a statement is imported first and the SMS scan runs after it (the scan does not look for
 * rows a statement already made), or when the two spell the merchant differently so the import's match missed.
 */
data class StatementDuplicate(val keep: TransactionEntity, val drop: TransactionEntity)

object StatementDuplicates {

    /**
     * Purchases announced twice, found with the rows a card's statements printed: see [com.moneytracker.app.data.rewards.StatementReconciler].
     * Only cards whose statements print rows (ICICI) can be checked this way.
     */
    fun findAdjacent(transactions: List<TransactionEntity>, entries: List<StatementRewardEntry>): List<StatementDuplicate> =
        entries.filter { it.rows.isNotEmpty() }.groupBy { it.bankName.trim().lowercase() to it.cardLast4 }.flatMap { (card, cardEntries) ->
            val mine = transactions.filter { it.accountNumber == card.second && it.bankName.orEmpty().trim().lowercase() == card.first }
            com.moneytracker.app.data.rewards.StatementReconciler.reconcile(mine, cardEntries).duplicates
        }

    private fun TransactionEntity.fromStatement() = smsSender?.endsWith("CC PDF") == true

    /**
     * Pairs each statement row with one SMS row of the same card, amount and type, one to one: two SMS rows for the
     * same amount need two statement rows to be both matched. The SMS row is kept, as it has the exact time.
     * Rows on the same day pair first; a statement row and an SMS row left over, with no partner of their own,
     * pair if they are a day apart (an SMS just after midnight, or a purchase that posted a day off).
     */
    fun find(transactions: List<TransactionEntity>): List<StatementDuplicate> {
        data class Key(val bank: String, val account: String, val amount: String, val type: String)
        fun TransactionEntity.key() = Key(
            bankName.orEmpty().trim().lowercase(), accountNumber.orEmpty(), amount.stripTrailingZeros().toPlainString(), transactionType.name
        )
        val live = transactions.filter { !it.isDeleted && it.accountNumber != null }
        return live.groupBy { it.key() }.values.flatMap { group ->
            val sms = group.filter { !it.fromStatement() }.sortedBy { it.dateTime }.toMutableList()
            val statement = group.filter { it.fromStatement() }.sortedBy { it.id }.toMutableList()
            val pairs = ArrayList<StatementDuplicate>()
            fun day(t: TransactionEntity) = t.dateTime.toLocalDate().toEpochDay()
            // Same day first.
            for (st in statement.toList()) {
                val partner = sms.firstOrNull { day(it) == day(st) } ?: continue
                pairs += StatementDuplicate(partner, st); sms.remove(partner); statement.remove(st)
            }
            // Then a day apart.
            for (st in statement.toList()) {
                val partner = sms.filter { Math.abs(day(it) - day(st)) == 1L }.minByOrNull { it.dateTime } ?: continue
                pairs += StatementDuplicate(partner, st); sms.remove(partner); statement.remove(st)
            }
            pairs
        }
    }
}
