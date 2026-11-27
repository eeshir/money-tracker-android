package com.moneytracker.app.data.statement

import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import java.time.Duration

/**
 * A transaction that is in the app twice: once read from an SMS and once from a card or bank statement.
 * That happens when a statement is imported first and the SMS scan runs after it, or when the import's match
 * missed it (a refund whose SMS came days late).
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

    /** How long a card's refund or payment SMS can trail the statement row's date. */
    private const val CARD_MONEY_IN_LAG = 5L

    /** A bank statement's row is the value date; its SMS can come up to this much later. */
    private const val BANK_LAG = 2L

    /** An SMS stamped this long after a statement was imported cannot be one of its rows. */
    private val IMPORT_SLACK: Duration = Duration.ofHours(1)

    fun isStatementRow(t: TransactionEntity) =
        t.smsSender?.endsWith("PDF") == true || t.smsSender == StatementTableParser.STATEMENT_SENDER

    fun accountKey(t: TransactionEntity): String = t.bankName.orEmpty().trim().lowercase() + "|" + t.accountNumber.orEmpty()

    private fun TransactionEntity.fromCardStatement() = smsSender?.endsWith("CC PDF") == true

    /** The accounts in [transactions] that are cards: ones with a card statement row or a card purchase. */
    fun cardAccountsOf(transactions: List<TransactionEntity>): Set<String> = transactions
        .filter { it.accountNumber != null && (it.fromCardStatement() || it.transactionType == TransactionType.CREDIT) }
        .mapTo(HashSet(), ::accountKey)

    /** A card's spare balance sent to the bank account: money leaving the card, though it reads like a refund. */
    private val CARD_TO_BANK = Regex("""\bfrom\b.{0,40}credit card.{0,20}\bto savings account""", RegexOption.IGNORE_CASE)

    private val UPI_NUMBER = Regex("""\d{12}""")
    private val SPACES = Regex("""\s""")

    /**
     * What a bank row's own narration says about [sms]: true when it quotes the SMS's UPI number, false when it is a
     * UPI row quoting a different one (so it is another payment, whatever the amount and date), null when it cannot
     * tell. The number can wrap over two lines, so spaces are ignored.
     */
    private fun quotes(row: TransactionEntity, sms: TransactionEntity): Boolean? {
        val number = sms.reference?.trim()?.takeIf { UPI_NUMBER.matches(it) } ?: return null
        val narration = row.smsBody?.replace(SPACES, "") ?: return null
        if (number in narration) return true
        val isUpi = "UPI/" in narration && sms.smsBody?.contains("UPI", ignoreCase = true) == true
        return if (isUpi && UPI_NUMBER.containsMatchIn(narration)) false else null
    }

    /** Days the SMS [sms] trails the statement row [row], or null when they cannot be the same transaction. */
    private fun lag(sms: TransactionEntity, row: TransactionEntity, cardAccounts: Set<String>): Long? {
        if (sms.amount.compareTo(row.amount) != 0 || sms.currency != row.currency) return null
        if (!sms.bankName.orEmpty().trim().equals(row.bankName.orEmpty().trim(), ignoreCase = true)) return null
        val moneyIn = row.transactionType == TransactionType.INCOME
        if ((sms.transactionType == TransactionType.INCOME) != moneyIn) return null
        if (sms.dateTime.isAfter(row.createdAt.plus(IMPORT_SLACK))) return null
        if (moneyIn && sms.smsBody?.let(CARD_TO_BANK::containsMatchIn) == true) return null
        val days = sms.dateTime.toLocalDate().toEpochDay() - row.dateTime.toLocalDate().toEpochDay()
        val allowed = if (row.fromCardStatement()) {
            if (sms.accountNumber == null || sms.accountNumber != row.accountNumber || sms.transactionType != row.transactionType) return null
            -1L..(if (moneyIn) CARD_MONEY_IN_LAG else 1L)
        } else {
            // A bank statement often names no account. Only a real SMS counts, and never a card's.
            if (sms.smsBody == null || sms.transactionType == TransactionType.CREDIT) return null
            if (accountKey(sms) in cardAccounts) return null
            if (!StatementTransactionEnricher.accountsMatch(sms.accountNumber, row.accountNumber)) return null
            -1L..BANK_LAG
        }
        return days.takeIf { it in allowed }
    }

    /**
     * Pairs each statement row with one SMS row, one to one: two SMS rows for the same amount need two statement
     * rows to be both matched, so a repeated purchase or transfer is never removed. The SMS row is kept, as it has
     * the exact time. Pairs with the same bank reference come first, then the closest dates. Deleted statement rows in
     * [transactions] claim their SMS before any live row can.
     */
    fun find(
        transactions: List<TransactionEntity>,
        cardAccounts: Set<String> = cardAccountsOf(transactions)
    ): List<StatementDuplicate> {
        // A removed statement row is still passed in: the SMS that stands in for it is taken, and must not be paired again.
        val (rows, others) = transactions.partition(::isStatementRow)
        val smsRows = others.filter { !it.isDeleted }
        val smsByAmount = smsRows.groupBy { it.amount.stripTrailingZeros() }

        data class Candidate(val sms: TransactionEntity, val row: TransactionEntity, val lag: Long, val sameReference: Boolean)

        val candidates = rows.flatMap { row ->
            smsByAmount[row.amount.stripTrailingZeros()].orEmpty().mapNotNull { sms ->
                val days = lag(sms, row, cardAccounts) ?: return@mapNotNull null
                val reference = StatementTransactionEnricher.normalizedReference(row.reference)
                val quoted = if (row.fromCardStatement()) null else quotes(row, sms)
                if (quoted == false) return@mapNotNull null
                val same = quoted == true ||
                    reference != null && reference == StatementTransactionEnricher.normalizedReference(sms.reference)
                Candidate(sms, row, days, same)
            }
        }.sortedWith(
            compareByDescending<Candidate> { it.row.isDeleted }
                .thenByDescending { it.sameReference }
                .thenBy { Math.abs(it.lag) }
                .thenBy { it.sms.dateTime }
                .thenBy { it.row.id }
        )

        val taken = HashSet<Long>()
        return candidates.mapNotNull { c ->
            if (c.sms.id in taken || c.row.id in taken) return@mapNotNull null
            taken += c.sms.id
            taken += c.row.id
            if (c.row.isDeleted) null else StatementDuplicate(keep = c.sms, drop = c.row)
        }
    }
}
