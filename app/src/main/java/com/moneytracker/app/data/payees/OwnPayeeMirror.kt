package com.moneytracker.app.data.payees

import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import com.moneytracker.app.data.repository.AccountBalanceRepository
import com.moneytracker.app.data.repository.TransactionRepository
import com.moneytracker.app.data.statement.SavedStatementStore
import com.moneytracker.app.data.statement.StatementPasswordStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies the person's [OwnPayeeRule]s to their transactions.
 *
 * A debit to one of their own accounts is not spending, and it has a credit on the
 * other side. That credit is either already there (the other account texted too)
 * — the two are paired and both left out of spending and income — or it is
 * recorded here and moved onto the target account's balance. Safe to run any
 * number of times: a credit that exists is found, never created twice.
 */
@Singleton
class OwnPayeeMirror @Inject constructor(
    private val rules: OwnPayeeRuleStore,
    private val transactionRepository: TransactionRepository,
    private val accountBalanceRepository: AccountBalanceRepository,
    private val savedStatements: SavedStatementStore
) {
    data class Outcome(val paired: Int, val created: Int)

    private val lock = Mutex()

    /** Runs the rules over the last [sinceDays] days of transactions. */
    suspend fun applyAll(sinceDays: Long = 120): Outcome = lock.withLock {
        withContext(Dispatchers.IO) {
            val ruleList = rules.all()
            if (ruleList.isEmpty()) return@withContext Outcome(0, 0)
            val now = LocalDateTime.now()
            val transactions = transactionRepository.getTransactionsBetweenDatesList(now.minusDays(sinceDays), now.plusDays(1))
            val taken = HashSet<Long>()
            var paired = 0
            var created = 0
            for (debit in transactions.filter { it.transactionType == TransactionType.EXPENSE }.sortedBy { it.dateTime }) {
                val rule = OwnPayeeMatcher.ruleFor(ruleList, debit) ?: continue
                val credit = OwnPayeeMatcher.counterpartOf(debit, rule, transactions, taken)
                if (credit != null) {
                    taken += credit.id
                    if (!debit.excludedFromAnalytics || !credit.excludedFromAnalytics) paired++
                    exclude(debit)
                    exclude(credit)
                } else if (rule.hasTarget && mirror(debit, rule.targetBank!!, rule.targetLast4!!)) {
                    exclude(debit)
                    created++
                }
            }
            Outcome(paired, created)
        }
    }

    private suspend fun exclude(transaction: TransactionEntity) {
        if (!transaction.excludedFromAnalytics) transactionRepository.updateTransaction(transaction.copy(excludedFromAnalytics = true))
    }

    /** Records [debit]'s missing credit on the target account. False when it can't be (no such account). */
    private suspend fun mirror(debit: TransactionEntity, targetBank: String, targetLast4: String): Boolean {
        accountBalanceRepository.getLatestBalance(targetBank, targetLast4) ?: return false
        val from = "${debit.bankName} ••${debit.accountNumber}"
        val credit = TransactionEntity(
            amount = debit.amount,
            merchantName = from,
            category = debit.category,
            transactionType = TransactionType.INCOME,
            dateTime = debit.dateTime,
            smsBody = "Credit for the debit from $from to ${debit.merchantName} (own account)",
            bankName = targetBank,
            accountNumber = targetLast4,
            transactionHash = hashOf(debit),
            excludedFromAnalytics = true,
            currency = debit.currency
        )
        val id = transactionRepository.insertTransaction(credit)
        if (id == -1L) return true // already recorded
        if (movesBalance(targetBank, targetLast4, debit.dateTime)) {
            accountBalanceRepository.applyTransactionToBalance(targetBank, targetLast4, debit.amount, TransactionType.INCOME, id)
        }
        return true
    }

    /**
     * Whether a credit dated [at] still has to move the account's balance. Anything
     * before the account's last reconciliation is already inside it. A card whose
     * SMS state the available limit needs no help: that figure already reflects
     * every payment.
     */
    private suspend fun movesBalance(bank: String, last4: String, at: LocalDateTime): Boolean {
        val latest = accountBalanceRepository.getLatestBalance(bank, last4) ?: return false
        if (latest.isCreditCard) {
            if (accountBalanceRepository.reportsAvailableLimit(bank, last4)) return false
            val key = StatementPasswordStore.entryKey(bank, last4)
            val appliedDay = savedStatements.list().filter { it.accountKey == key && it.appliedAt > 0 }.maxOfOrNull { it.statementDay }
            return appliedDay == null || at.toLocalDate() > LocalDate.ofEpochDay(appliedDay)
        }
        val anchor = accountBalanceRepository.latestManualAnchor(bank, last4)
        return anchor == null || at > anchor
    }

    private fun hashOf(debit: TransactionEntity): String =
        MessageDigest.getInstance("SHA-256").digest("own-payee|${debit.transactionHash}".toByteArray())
            .joinToString("") { "%02x".format(it) }
}
