package com.moneytracker.app.presentation.refunds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneytracker.app.data.database.dao.TransactionDao
import com.moneytracker.app.data.database.dao.TransactionSplitDao
import com.moneytracker.app.data.database.entity.RefundAllocationEntity
import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.refunds.RefundLinks
import com.moneytracker.app.data.repository.RefundLinkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/** One refund that can be put on the expense, with how much of it is still available for this part. */
data class RefundOption(
    val refund: TransactionEntity,
    /** What the refund can put here: its floater plus what it already puts on this part. */
    val available: BigDecimal
)

data class ExpenseLinkState(
    val expense: TransactionEntity? = null,
    /** The category parts of a split expense, or just null for an expense with no parts. */
    val parts: List<String?> = listOf(null),
    val costOfPart: Map<String?, BigDecimal> = emptyMap(),
    val part: String? = null,
    val refunds: List<TransactionEntity> = emptyList(),
    val allocations: List<RefundAllocationEntity> = emptyList(),
    /** What the person has typed, by refund id. */
    val inputs: Map<Long, String> = emptyMap(),
    val saving: Boolean = false,
    val notice: Boolean = false,
    val saved: Boolean = false,
    val unavailable: Boolean = false
) {
    private fun amountOf(refundId: Long) = inputs[refundId]?.trim()?.replace(",", "")?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }

    private fun here(refundId: Long) = allocations.filter { it.refundId == refundId && it.expenseId == expense?.id && it.category == part }
        .fold(BigDecimal.ZERO) { a, x -> a + x.amount }

    /** The refunds that can put money on the chosen part: those with a floater, and those already linked to it. */
    val options: List<RefundOption>
        get() = refunds.mapNotNull { r ->
            val linked = allocations.filter { it.refundId == r.id }.fold(BigDecimal.ZERO) { a, x -> a + x.amount }
            val available = (r.amount - linked).max(BigDecimal.ZERO) + here(r.id)
            if (available.signum() > 0) RefundOption(r, available) else null
        }

    val cost: BigDecimal get() = costOfPart[part] ?: BigDecimal.ZERO
    val typedTotal: BigDecimal get() = options.fold(BigDecimal.ZERO) { a, o -> a + (amountOf(o.refund.id) ?: BigDecimal.ZERO) }
    val overCost: Boolean get() = typedTotal > cost
    val overRefund: Boolean get() = options.any { (amountOf(it.refund.id) ?: BigDecimal.ZERO) > it.available }
    val canSave: Boolean get() = expense != null && !saving && !overCost && !overRefund

    fun amounts(): Map<Long, BigDecimal> = options.associate { it.refund.id to (amountOf(it.refund.id) ?: BigDecimal.ZERO) }

    /** What each refund already puts on the chosen part, to show as the starting values. */
    fun existing(): Map<Long, String> = options.mapNotNull { o ->
        val h = here(o.refund.id)
        if (h.signum() > 0) o.refund.id to h.stripTrailingZeros().toPlainString() else null
    }.toMap()
}

/**
 * The other side of linking: from an expense, choose which refunds pay it back and how much each puts on it.
 * Saving changes only each refund's link to this expense (or to the chosen part of it).
 */
@HiltViewModel
class ExpenseRefundLinkViewModel @Inject constructor(
    private val links: RefundLinkRepository,
    private val transactions: TransactionDao,
    private val splitDao: TransactionSplitDao
) : ViewModel() {

    private val _state = MutableStateFlow(ExpenseLinkState())
    val state: StateFlow<ExpenseLinkState> = _state.asStateFlow()

    private var loadedFor: Long? = null

    /** Whether any refund in [currency] has money left to put on an expense: decides if the button is worth showing. */
    fun hasRefundsToLink(currency: String): Flow<Boolean> =
        combine(links.observeLinkManagedRefunds(), links.observeLiveAllocations()) { refunds, allocations ->
            val byRefund = allocations.groupBy { it.refundId }
            refunds.any { r -> r.currency == currency && RefundLinks.floater(r, byRefund[r.id].orEmpty()).signum() > 0 }
        }

    fun load(expenseId: Long) {
        if (loadedFor == expenseId) return
        loadedFor = expenseId
        viewModelScope.launch {
            val expense = transactions.getTransactionById(expenseId)
            if (expense == null || expense.isDeleted || expense.excludedFromAnalytics || expense.loanId != null || expense.lentSharesTotal != null ||
                (expense.transactionType != com.moneytracker.app.data.database.entity.TransactionType.EXPENSE &&
                    expense.transactionType != com.moneytracker.app.data.database.entity.TransactionType.CREDIT)
            ) {
                _state.value = ExpenseLinkState(unavailable = true)
                return@launch
            }
            val splits = splitDao.getSplitsForTransactionSync(expenseId)
            val parts: List<String?> = if (splits.isEmpty()) listOf(null) else splits.map { it.category }.distinct()
            val costOfPart: Map<String?, BigDecimal> =
                if (splits.isEmpty()) mapOf(null to expense.amount)
                else splits.groupBy { it.category }.mapValues { (_, v) -> v.fold(BigDecimal.ZERO) { a, x -> a + x.amount } }
            val refunds = links.observeLinkManagedRefunds().first().filter { RefundLinks.canLink(it, expense) }
            val allocations = links.observeLiveAllocations().first()
            val base = ExpenseLinkState(expense = expense, parts = parts, costOfPart = costOfPart, part = parts.first(), refunds = refunds, allocations = allocations)
            _state.value = base.copy(inputs = base.existing())
        }
    }

    fun selectPart(part: String?) = _state.update { s ->
        val next = s.copy(part = part, notice = false)
        next.copy(inputs = next.existing())
    }

    fun setAmount(refundId: Long, text: String) = _state.update { it.copy(inputs = it.inputs + (refundId to text), notice = false) }

    fun clear(refundId: Long) = _state.update { it.copy(inputs = it.inputs - refundId, notice = false) }

    /** Fills [refundId] with as much as it has available and the part still has room for. */
    fun fill(refundId: Long) = _state.update { s ->
        val option = s.options.firstOrNull { it.refund.id == refundId } ?: return@update s
        val others = s.options.filter { it.refund.id != refundId }.fold(BigDecimal.ZERO) { a, o ->
            a + (s.inputs[o.refund.id]?.trim()?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: BigDecimal.ZERO)
        }
        val give = option.available.min(s.cost - others).max(BigDecimal.ZERO)
        s.copy(inputs = s.inputs + (refundId to give.stripTrailingZeros().toPlainString()), notice = false)
    }

    fun save() {
        val s = _state.value
        val expense = s.expense ?: return
        if (!s.canSave) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val problems = links.setExpenseLinks(expense.id, s.part, s.amounts())
            _state.update { it.copy(saving = false, saved = problems.isEmpty(), notice = problems.isNotEmpty()) }
        }
    }

    fun consumeSaved() = _state.update { it.copy(saved = false) }

    fun reset() {
        loadedFor = null
        _state.value = ExpenseLinkState()
    }
}
