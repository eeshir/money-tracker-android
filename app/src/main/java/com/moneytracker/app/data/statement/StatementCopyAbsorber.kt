package com.moneytracker.app.data.statement

import com.moneytracker.app.data.database.entity.CardType
import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.repository.CardRepository
import com.moneytracker.app.data.repository.TransactionRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When an SMS is saved for a transaction a statement already added, removes the statement's copy.
 * It looks only at the row just saved; copies that were already there are left for the person to review.
 */
@Singleton
class StatementCopyAbsorber @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val cardRepository: CardRepository,
    private val separateRows: SeparateRowStore
) {
    /** @return whether a statement copy of the saved SMS row [savedId] was removed. */
    suspend fun absorb(savedId: Long): Boolean {
        val saved = transactionRepository.getTransactionById(savedId) ?: return false
        if (StatementDuplicates.isStatementRow(saved)) return false
        val rows = transactionRepository
            // By date, not amount: the amount query compares text, and "199" is not "199.00" to it.
            .getTransactionsBetweenDatesList(saved.dateTime.minusDays(SEARCH_DAYS), saved.dateTime.plusDays(SEARCH_DAYS))
            .filter { !it.isDeleted && it.amount.compareTo(saved.amount) == 0 }
            .filter { StatementDuplicates.isStatementRow(it) && it.transactionHash !in separateRows.hashes.value }
            // A copy the person has linked to something stays, for them to review.
            .filterNot { it.hasLinks() }
        if (rows.isEmpty()) return false
        val isCard = saved.accountNumber?.let { cardRepository.getCard(saved.bankName.orEmpty(), it)?.cardType } == CardType.CREDIT
        val cardAccounts = if (isCard) setOf(StatementDuplicates.accountKey(saved)) else emptySet()
        val pair = StatementDuplicates.find(rows + saved, cardAccounts).firstOrNull { it.keep.id == savedId } ?: return false
        if (!transactionRepository.deleteDuplicate(pair.drop.id, savedId)) return false
        // The statement often spells the other party out in full where the SMS has nothing useful.
        val merged = StatementTransactionEnricher.enrich(saved, pair.drop)
        if (StatementTransactionEnricher.hasEnrichment(saved, merged)) transactionRepository.updateTransaction(merged)
        return true
    }

    private fun TransactionEntity.hasLinks() =
        loanId != null || lentSharesTotal != null || (refundedTotal?.signum() ?: 0) > 0

    private companion object {
        const val SEARCH_DAYS = 6L
    }
}
