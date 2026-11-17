package com.moneytracker.app.data.statement

import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import com.moneytracker.app.data.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A card bill payment shows up twice: as the statement's own row (date only) and as the card's SMS,
 * which can come days after that date. Neither counts in analytics, but both lower the card's
 * balance, so the card reads too low. This keeps the SMS (it has the real time) and removes the
 * statement's copy.
 */
object CardPaymentPairs {
    /** The widest gap between a statement's payment row and the SMS for the same payment. */
    val WINDOW: Duration = Duration.ofDays(5)

    fun isStatementRow(t: TransactionEntity) = t.smsSender?.endsWith("PDF") == true

    fun isCardPayment(t: TransactionEntity) =
        !t.isDeleted && t.transactionType == TransactionType.INCOME && t.excludedFromAnalytics &&
            !t.accountNumber.isNullOrBlank()

    /** Statement-row id → the SMS row that stays, for every statement payment an SMS already records. */
    fun duplicatesToKeepers(transactions: List<TransactionEntity>): Map<Long, Long> {
        val result = LinkedHashMap<Long, Long>()
        val taken = HashSet<Long>()
        val payments = transactions.filter(::isCardPayment)
        val sms = payments.filter { !isStatementRow(it) }
        for (row in payments.filter(::isStatementRow).sortedBy { it.dateTime }) {
            val keeper = sms.filter {
                it.id !in taken && it.bankName == row.bankName && it.accountNumber == row.accountNumber &&
                    it.amount.compareTo(row.amount) == 0 && it.currency == row.currency &&
                    Duration.between(it.dateTime, row.dateTime).abs() <= WINDOW
            }.minByOrNull { Duration.between(it.dateTime, row.dateTime).abs() } ?: continue
            taken += keeper.id
            result[row.id] = keeper.id
        }
        return result
    }
}

@Singleton
class CardPaymentDeduper @Inject constructor(
    private val transactionRepository: TransactionRepository
) {
    private val lock = Mutex()

    /** Removes statement payment rows that an SMS already records; returns how many. */
    suspend fun run(): Int = lock.withLock {
        withContext(Dispatchers.IO) {
            val rows = transactionRepository.getTransactionsBetweenDatesList(
                LocalDateTime.now().minusDays(400), LocalDateTime.now().plusDays(1)
            )
            CardPaymentPairs.duplicatesToKeepers(rows).count { (dup, keeper) ->
                transactionRepository.deleteDuplicate(dup, keeper)
            }
        }
    }
}
