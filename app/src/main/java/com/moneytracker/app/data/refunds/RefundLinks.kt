package com.moneytracker.app.data.refunds

import com.moneytracker.app.data.database.entity.BudgetImpactType
import com.moneytracker.app.data.database.entity.RefundAllocationEntity
import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionSplitEntity
import com.moneytracker.app.data.database.entity.TransactionType
import java.math.BigDecimal

/**
 * The rules for linking a refund to the expenses it pays back. Pure functions, no database.
 *
 * A refund is an INCOME marked [BudgetImpactType.DEDUCT_SPENT]. Its amount can be shared out over expenses
 * (and over the category parts of a split expense); what is not shared out is the floater, which counts for
 * nothing until it is linked. An expense can never be refunded for more than it cost.
 */
object RefundLinks {

    /** True for an income that pays back spending and has no category of its own: its effect comes only from its links. */
    fun isLinkManaged(t: TransactionEntity) =
        t.transactionType == TransactionType.INCOME &&
            t.budgetImpactType == BudgetImpactType.DEDUCT_SPENT &&
            t.budgetCategory == null

    /** Whether [refund] can have links at all. */
    fun isRefund(refund: TransactionEntity) =
        !refund.isDeleted && refund.transactionType == TransactionType.INCOME &&
            refund.budgetImpactType == BudgetImpactType.DEDUCT_SPENT

    /**
     * Whether [expense] can be paid back by [refund]: a live spend in the same currency, counted in full by the totals.
     * Loan-linked and shared-bill expenses are left out because their counted part is already cut by another rule.
     */
    fun canLink(refund: TransactionEntity, expense: TransactionEntity) =
        !expense.isDeleted && !expense.excludedFromAnalytics &&
            (expense.transactionType == TransactionType.EXPENSE || expense.transactionType == TransactionType.CREDIT) &&
            expense.currency == refund.currency && expense.id != refund.id &&
            expense.loanId == null && expense.lentSharesTotal == null

    /**
     * What of [refund] is not linked to any expense: its floater. [linked] are its allocations on expenses that still exist.
     * Never negative, even if the refund was edited down below what it had linked.
     */
    fun floater(refund: TransactionEntity, linked: List<RefundAllocationEntity>): BigDecimal =
        (refund.amount - linked.fold(BigDecimal.ZERO) { a, x -> a + x.amount }).max(BigDecimal.ZERO)

    /** One wish: "put [amount] of this refund on [expenseId] ([category] part, or null for the whole expense)". */
    data class Request(val expenseId: Long, val category: String?, val amount: BigDecimal)

    class Outcome(
        /** The requests that fit, each cut down to what is left on its expense. */
        val accepted: List<Request>,
        /** Requests that were cut or refused, so the screen can say why. */
        val problems: List<Problem>
    ) {
        val total: BigDecimal get() = accepted.fold(BigDecimal.ZERO) { a, r -> a + r.amount }
    }

    sealed class Problem {
        data class UnknownExpense(val expenseId: Long) : Problem()
        data class NotLinkable(val expenseId: Long) : Problem()
        data class NotPositive(val expenseId: Long) : Problem()
        data class CutToRoom(val expenseId: Long, val asked: BigDecimal, val given: BigDecimal) : Problem()
        data class OverRefund(val asked: BigDecimal, val given: BigDecimal) : Problem()
    }

    /** What an expense looks like to the linker. [others] are allocations from other refunds that are already live. */
    class Target(
        val expense: TransactionEntity,
        val splits: List<TransactionSplitEntity>,
        val others: List<RefundAllocationEntity>
    )

    /**
     * Fits [requests] for [refund] to the room left on each target, in the order given, and never puts more on
     * the refund than its amount. Room on a split expense is tracked per category as well as for the whole, so a
     * category can't take more than its part and the parts together can't take more than the expense.
     */
    fun plan(refund: TransactionEntity, requests: List<Request>, targets: Map<Long, Target>): Outcome {
        val accepted = mutableListOf<Request>()
        val problems = mutableListOf<Problem>()
        var left = refund.amount
        val usedWhole = HashMap<Long, BigDecimal>()
        val usedCategory = HashMap<Pair<Long, String>, BigDecimal>()
        for (t in targets.values) {
            usedWhole[t.expense.id] = t.others.fold(BigDecimal.ZERO) { a, x -> a + x.amount }
            t.others.filter { it.category != null }.groupBy { it.category!! }.forEach { (c, list) ->
                usedCategory[t.expense.id to c] = list.fold(BigDecimal.ZERO) { a, x -> a + x.amount }
            }
        }
        for (r in requests) {
            val target = targets[r.expenseId]
            if (target == null) { problems += Problem.UnknownExpense(r.expenseId); continue }
            if (!canLink(refund, target.expense)) { problems += Problem.NotLinkable(r.expenseId); continue }
            if (r.amount.signum() <= 0) { problems += Problem.NotPositive(r.expenseId); continue }
            var give = r.amount
            give = give.min(target.expense.amount - (usedWhole[r.expenseId] ?: BigDecimal.ZERO))
            val category = r.category?.takeIf { c -> target.splits.any { it.category == c } }
            if (category != null) {
                val capacity = target.splits.filter { it.category == category }.fold(BigDecimal.ZERO) { a, s -> a + s.amount }
                give = give.min(capacity - (usedCategory[r.expenseId to category] ?: BigDecimal.ZERO))
            }
            give = give.max(BigDecimal.ZERO)
            if (give.compareTo(left) > 0) {
                problems += Problem.OverRefund(r.amount, left.max(BigDecimal.ZERO))
                give = left.max(BigDecimal.ZERO)
            } else if (give.compareTo(r.amount) < 0) {
                problems += Problem.CutToRoom(r.expenseId, r.amount, give)
            }
            if (give.signum() == 0) continue
            accepted += Request(r.expenseId, category, give)
            left -= give
            usedWhole[r.expenseId] = (usedWhole[r.expenseId] ?: BigDecimal.ZERO) + give
            if (category != null) {
                val key = r.expenseId to category
                usedCategory[key] = (usedCategory[key] ?: BigDecimal.ZERO) + give
            }
        }
        return Outcome(accepted, problems)
    }

    /** The stored copies for one expense: [total] and each split's part, from its live allocations. */
    class Stored(val total: BigDecimal?, val bySplitId: Map<Long, BigDecimal>)

    /**
     * Works out [TransactionEntity.refundedTotal] and each [TransactionSplitEntity.refundedAmount] for [expense].
     * Never more than the expense (or split) cost, even if the expense was edited down after it was linked. An
     * allocation to a category that no longer exists still counts toward the total, not to any split.
     */
    fun stored(expense: TransactionEntity, splits: List<TransactionSplitEntity>, live: List<RefundAllocationEntity>): Stored {
        val sum = live.fold(BigDecimal.ZERO) { a, x -> a + x.amount }
        val total = sum.min(expense.amount).max(BigDecimal.ZERO)
        val bySplit = HashMap<Long, BigDecimal>()
        live.filter { it.category != null }.groupBy { it.category!! }.forEach { (category, list) ->
            var remaining = list.fold(BigDecimal.ZERO) { a, x -> a + x.amount }
            for (s in splits.filter { it.category == category }) {
                if (remaining.signum() <= 0) break
                val part = remaining.min(s.amount)
                bySplit[s.id] = part
                remaining -= part
            }
        }
        return Stored(total.takeIf { it.signum() > 0 }, bySplit.filterValues { it.signum() > 0 })
    }
}
