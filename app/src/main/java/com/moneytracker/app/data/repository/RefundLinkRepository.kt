package com.moneytracker.app.data.repository

import androidx.room.withTransaction
import com.moneytracker.app.data.database.PennyWiseDatabase
import com.moneytracker.app.data.database.dao.RefundAllocationDao
import com.moneytracker.app.data.database.dao.TransactionDao
import com.moneytracker.app.data.database.dao.TransactionSplitDao
import com.moneytracker.app.data.database.entity.RefundAllocationEntity
import com.moneytracker.app.data.refunds.RefundLinks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Links refunds to the expenses they pay back, and keeps the copies the totals read
 * (`transactions.refunded_total`, `transaction_splits.refunded_amount`) in step with the allocations.
 * The rules themselves are in [RefundLinks].
 */
@Singleton
class RefundLinkRepository @Inject constructor(
    private val database: PennyWiseDatabase,
    private val allocations: RefundAllocationDao,
    private val transactions: TransactionDao,
    private val splits: TransactionSplitDao
) {
    fun observeForRefund(refundId: Long): Flow<List<RefundAllocationEntity>> = allocations.observeForRefund(refundId)

    fun observeForExpense(expenseId: Long): Flow<List<RefundAllocationEntity>> = allocations.observeForExpense(expenseId)

    /** Refunds that count only through their links, live. */
    fun observeLinkManagedRefunds(): Flow<List<com.moneytracker.app.data.database.entity.TransactionEntity>> = transactions.observeLinkManagedRefunds()

    /** Allocations on expenses that still exist, live. */
    fun observeLiveAllocations(): Flow<List<RefundAllocationEntity>> = allocations.observeAll()

    /** What a transaction was before it was made a refund just to be linked; see [beginLinking]. */
    class Previous(val impact: com.moneytracker.app.data.database.entity.BudgetImpactType?, val category: String?)

    /**
     * Called when the person asks to link an income that is not yet a saved refund (they just switched it to Refund and
     * have not saved). Makes it a refund right away so it can be linked, and returns what it was, so [restore] can put
     * that back if they leave without linking. Null when there is nothing to undo: it already is a refund, or it cannot be.
     */
    suspend fun beginLinking(transactionId: Long): Previous? = database.withTransaction {
        val tx = transactions.getTransactionById(transactionId)
        if (tx == null || tx.isDeleted || tx.transactionType != com.moneytracker.app.data.database.entity.TransactionType.INCOME || RefundLinks.isRefund(tx)) {
            return@withTransaction null
        }
        val before = Previous(tx.budgetImpactType, tx.budgetCategory)
        transactions.setBudgetImpact(transactionId, com.moneytracker.app.data.database.entity.BudgetImpactType.DEDUCT_SPENT, null, LocalDateTime.now())
        onChanged(transactionId)
        before
    }

    /** Puts back what [beginLinking] returned. */
    suspend fun restore(transactionId: Long, before: Previous) = database.withTransaction {
        transactions.setBudgetImpact(transactionId, before.impact, before.category, LocalDateTime.now())
        onChanged(transactionId)
    }

    /** Live: what each refund has linked so far, by refund id, for the list rows. */
    fun observeLinkedByRefund(): Flow<Map<Long, BigDecimal>> = allocations.observeAll().map { list ->
        list.groupBy { it.refundId }.mapValues { (_, v) -> v.fold(BigDecimal.ZERO) { a, x -> a + x.amount } }
    }

    /** Every allocation, for the backup. */
    suspend fun getAll(): List<RefundAllocationEntity> = allocations.getAll()

    /**
     * Replaces the links of [refundId] with [requests], cut to what fits (see [RefundLinks.plan]).
     * A refund with links drops its own category: from then on its effect comes only from the links.
     * Passing an empty list unlinks it entirely, leaving the whole amount as floater.
     */
    suspend fun setLinks(refundId: Long, requests: List<RefundLinks.Request>): RefundLinks.Outcome =
        database.withTransaction {
            val refund = transactions.getTransactionById(refundId)
            if (refund == null || !RefundLinks.isRefund(refund)) {
                return@withTransaction RefundLinks.Outcome(emptyList(), emptyList())
            }
            val before = allocations.expenseIdsForRefund(refundId)
            val ids = (requests.map { it.expenseId } + before).distinct()
            val targets = ids.mapNotNull { id ->
                val expense = transactions.getTransactionById(id) ?: return@mapNotNull null
                id to RefundLinks.Target(
                    expense,
                    splits.getSplitsForTransactionSync(id),
                    allocations.getLiveForExpense(id).filter { it.refundId != refundId }
                )
            }.toMap()
            val outcome = RefundLinks.plan(refund, requests, targets)
            allocations.deleteForRefund(refundId)
            val now = LocalDateTime.now()
            allocations.insertAll(outcome.accepted.map {
                RefundAllocationEntity(refundId = refundId, expenseId = it.expenseId, category = it.category, amount = it.amount, createdAt = now)
            })
            if (outcome.accepted.isNotEmpty() && refund.budgetCategory != null) {
                transactions.updateTransaction(refund.copy(budgetCategory = null, updatedAt = now))
            }
            refresh(ids)
            outcome
        }

    /** The expenses a refund has allocations on (to refresh them after the refund is deleted). */
    suspend fun expensesPaidBackBy(refundId: Long): List<Long> = allocations.expenseIdsForRefund(refundId)

    /**
     * Sets, from the expense side, how much each refund in [amounts] (refund id to amount, 0 clears it) puts on [expenseId]
     * ([part] = the category part of a split expense, or null for the whole). Each refund keeps its links to other expenses
     * and to other parts of this one; only its link to this part changes. Returns whatever had to be cut to fit.
     */
    suspend fun setExpenseLinks(expenseId: Long, part: String?, amounts: Map<Long, BigDecimal>): List<RefundLinks.Problem> =
        database.withTransaction {
            val problems = mutableListOf<RefundLinks.Problem>()
            for ((refundId, amount) in amounts) {
                val keep = allocations.getForRefund(refundId)
                    .filter { !(it.expenseId == expenseId && it.category == part) }
                    .map { RefundLinks.Request(it.expenseId, it.category, it.amount) }
                val requests = if (amount.signum() > 0) keep + RefundLinks.Request(expenseId, part, amount) else keep
                problems += setLinks(refundId, requests).problems
            }
            problems
        }

    /** Recomputes the stored refund copies of every expense in [expenseIds]. */
    suspend fun refresh(expenseIds: Collection<Long>) = database.withTransaction {
        for (id in expenseIds.distinct()) {
            val expense = transactions.getTransactionById(id) ?: continue
            val parts = splits.getSplitsForTransactionSync(id)
            val stored = RefundLinks.stored(expense, parts, allocations.getLiveForExpense(id))
            if (expense.refundedTotal.sameAs(stored.total)) {
                // unchanged total; the split parts below may still differ
            } else {
                transactions.setRefundedTotal(id, stored.total)
            }
            for (s in parts) {
                val want = stored.bySplitId[s.id]
                if (!s.refundedAmount.sameAs(want)) splits.setRefundedAmount(s.id, want)
            }
        }
    }

    /**
     * A transaction was edited, deleted or brought back: its own stored copies are re-derived (as an expense),
     * and so are those of the expenses it pays back (as a refund).
     */
    suspend fun onChanged(transactionId: Long) {
        val affected = allocations.expenseIdsForRefund(transactionId) + transactionId
        refresh(affected)
    }

    /** Re-derives every stored copy; run after a restore and once per launch to heal any drift. */
    suspend fun refreshAll() {
        refresh((allocations.linkedExpenseIds() + allocations.expenseIdsWithStoredTotal()).distinct())
    }

    private fun BigDecimal?.sameAs(other: BigDecimal?) =
        (this == null && other == null) || (this != null && other != null && this.compareTo(other) == 0)
}
