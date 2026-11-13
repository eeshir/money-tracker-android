package com.pennywiseai.tracker.presentation.refunds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.data.database.dao.RefundAllocationDao
import com.pennywiseai.tracker.data.database.dao.TransactionDao
import com.pennywiseai.tracker.data.database.dao.TransactionSplitDao
import com.pennywiseai.tracker.data.database.entity.RefundAllocationEntity
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionSplitEntity
import com.pennywiseai.tracker.data.refunds.RefundLinks
import com.pennywiseai.tracker.data.repository.RefundLinkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDateTime
import javax.inject.Inject

/** One place a refund can put money: a whole expense, or one category part of a split expense. */
data class LinkTarget(
    val expense: TransactionEntity,
    /** Null for the whole expense; the category name for one part of a split expense. */
    val category: String?,
    /** What this target cost. */
    val cost: BigDecimal,
    /** Already paid back by other refunds. */
    val takenByOthers: BigDecimal
) {
    val key: String get() = "${expense.id}|${category.orEmpty()}"
    val room: BigDecimal get() = (cost - takenByOthers).coerceAtLeast(BigDecimal.ZERO)
}

data class RefundLinkState(
    val refund: TransactionEntity? = null,
    val targets: List<LinkTarget> = emptyList(),
    /** What the person has typed, by [LinkTarget.key]. */
    val inputs: Map<String, String> = emptyMap(),
    val query: String = "",
    val saving: Boolean = false,
    /** Set after a save that had to cut something; null when all fit. */
    val notice: List<RefundLinks.Problem>? = null,
    val saved: Boolean = false,
    /** The loaded transaction is not a saved refund (e.g. it was just switched to Refund and not saved yet). */
    val unavailable: Boolean = false
) {
    private fun amountOf(key: String) = inputs[key]?.trim()?.replace(",", "")?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }

    val linked: BigDecimal get() = inputs.keys.fold(BigDecimal.ZERO) { a, k -> a + (amountOf(k) ?: BigDecimal.ZERO) }
    val floater: BigDecimal get() = (refund?.amount ?: BigDecimal.ZERO) - linked
    val overRefund: Boolean get() = floater.signum() < 0
    val overRoom: Boolean get() = targets.any { t -> (amountOf(t.key) ?: BigDecimal.ZERO) > t.room }
    val canSave: Boolean get() = refund != null && !saving && !overRefund && !overRoom

    val visibleTargets: List<LinkTarget>
        get() {
            val q = query.trim().lowercase()
            return if (q.isEmpty()) targets else targets.filter { t ->
                val e = t.expense
                e.merchantName.lowercase().contains(q) || e.category.lowercase().contains(q) ||
                    (t.category?.lowercase()?.contains(q) == true) || (e.description?.lowercase()?.contains(q) == true)
            }
        }

    fun requests(): List<RefundLinks.Request> =
        targets.mapNotNull { t -> amountOf(t.key)?.let { RefundLinks.Request(t.expense.id, t.category, it) } }
}

@HiltViewModel
class RefundLinkViewModel @Inject constructor(
    private val links: RefundLinkRepository,
    private val transactions: TransactionDao,
    private val splitDao: TransactionSplitDao,
    private val allocations: RefundAllocationDao
) : ViewModel() {

    private val _state = MutableStateFlow(RefundLinkState())
    val state: StateFlow<RefundLinkState> = _state.asStateFlow()

    private var loadedFor: Long? = null

    /** What the transaction was before opening the sheet made it a refund; put back if no links are saved. */
    private var revertTo: RefundLinkRepository.Previous? = null

    /** Loads the candidates and current links of [refundId]. A second call for the same refund keeps what was typed. */
    fun load(refundId: Long) {
        if (loadedFor == refundId) return
        loadedFor = refundId
        viewModelScope.launch {
            // Linking an income that was only just switched to Refund (not saved yet) makes it a refund now.
            revertTo = links.beginLinking(refundId)
            val refund = transactions.getTransactionById(refundId)
            if (refund == null || !RefundLinks.isRefund(refund)) {
                _state.value = RefundLinkState(unavailable = true)
                return@launch
            }
            val mine = allocations.getForRefund(refundId)
            val candidates = transactions.getLinkableExpenses(
                refund.currency, refund.dateTime.minusDays(365), mine.map { it.expenseId }.ifEmpty { listOf(-1L) }
            ).filter { RefundLinks.canLink(refund, it) }
            val splitsByTx = splitDao.getSplitsForTransactions(candidates.map { it.id }.ifEmpty { listOf(-1L) }).groupBy { it.transactionId }
            val others = allocations.getAllLive().filter { it.refundId != refundId }.groupBy { it.expenseId }
            val targets = candidates.flatMap { e -> targetsFor(e, splitsByTx[e.id].orEmpty(), others[e.id].orEmpty()) }
            val keys = targets.map { it.key }.toSet()
            val inputs = HashMap<String, BigDecimal>()
            for (a in mine) {
                val exact = "${a.expenseId}|${a.category.orEmpty()}"
                // A link to a part that no longer exists is shown against the expense's first part.
                val key = if (exact in keys) exact else targets.firstOrNull { it.expense.id == a.expenseId }?.key ?: continue
                inputs[key] = (inputs[key] ?: BigDecimal.ZERO) + a.amount
            }
            _state.value = RefundLinkState(
                refund = refund, targets = targets,
                inputs = inputs.mapValues { it.value.stripTrailingZeros().toPlainString() }
            )
        }
    }

    private fun targetsFor(e: TransactionEntity, parts: List<TransactionSplitEntity>, others: List<RefundAllocationEntity>): List<LinkTarget> {
        if (parts.isEmpty()) return listOf(LinkTarget(e, null, e.amount, others.fold(BigDecimal.ZERO) { a, x -> a + x.amount }))
        // A split expense is linked by category part; money other refunds put on the whole is charged to the first part.
        val wholeOthers = others.filter { o -> parts.none { p -> p.category == o.category } }.fold(BigDecimal.ZERO) { a, x -> a + x.amount }
        val groups = parts.groupBy { it.category }
        return groups.entries.mapIndexed { i, (category, list) ->
            val cost = list.fold(BigDecimal.ZERO) { a, x -> a + x.amount }
            val taken = others.filter { it.category == category }.fold(BigDecimal.ZERO) { a, x -> a + x.amount }
            LinkTarget(e, category, cost, taken + if (i == 0) wholeOthers else BigDecimal.ZERO)
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun setAmount(key: String, text: String) = _state.update { it.copy(inputs = it.inputs + (key to text), notice = null) }

    /** Fills [key] with as much as the refund still has and the target has room for. */
    fun fill(key: String) = _state.update { s ->
        val t = s.targets.firstOrNull { it.key == key } ?: return@update s
        val current = s.inputs[key]?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val give = (s.floater + current).min(t.room).max(BigDecimal.ZERO)
        s.copy(inputs = s.inputs + (key to give.stripTrailingZeros().toPlainString()), notice = null)
    }

    fun clear(key: String) = _state.update { it.copy(inputs = it.inputs - key, notice = null) }

    fun save() {
        val s = _state.value
        val refund = s.refund ?: return
        if (!s.canSave) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val outcome = links.setLinks(refund.id, s.requests())
            revertTo = null
            _state.update { it.copy(saving = false, saved = outcome.problems.isEmpty(), notice = outcome.problems.takeIf { p -> p.isNotEmpty() }) }
        }
    }

    /** The total linked so far from [refundId], live, for the summary lines. */
    fun linkedTotal(refundId: Long): kotlinx.coroutines.flow.Flow<BigDecimal> =
        links.observeForRefund(refundId).map { list -> list.fold(BigDecimal.ZERO) { a, x -> a + x.amount } }

    fun consumeSaved() = _state.update { it.copy(saved = false) }

    /**
     * The sheet is being closed without saving links. If opening it made the transaction a refund, it goes back to what it
     * was and [onDone] gets that, so the edit form can be reset too; otherwise [onDone] gets null.
     */
    fun dismiss(onDone: (RefundLinkRepository.Previous?) -> Unit) {
        val id = loadedFor
        val before = revertTo
        revertTo = null
        viewModelScope.launch {
            if (id != null && before != null) links.restore(id, before)
            reset()
            onDone(before)
        }
    }

    /** Forget the loaded refund so the next [load] starts fresh. */
    fun reset() {
        loadedFor = null
        _state.value = RefundLinkState()
    }
}
