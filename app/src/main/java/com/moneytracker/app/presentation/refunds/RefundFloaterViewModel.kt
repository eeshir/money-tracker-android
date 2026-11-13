package com.moneytracker.app.presentation.refunds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.refunds.RefundLinks
import com.moneytracker.app.data.repository.RefundLinkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.math.BigDecimal
import javax.inject.Inject

/** A refund that still has money not linked to any expense. */
data class RefundFloater(val refund: TransactionEntity, val amount: BigDecimal)

/** Feeds the Home card: every refund with a floater, newest first. Empty when everything is linked. */
@HiltViewModel
class RefundFloaterViewModel @Inject constructor(links: RefundLinkRepository) : ViewModel() {

    val floaters: StateFlow<List<RefundFloater>> =
        combine(links.observeLinkManagedRefunds(), links.observeLiveAllocations()) { refunds, allocations ->
            val byRefund = allocations.groupBy { it.refundId }
            refunds.mapNotNull { r ->
                val floater = RefundLinks.floater(r, byRefund[r.id].orEmpty())
                if (floater.signum() > 0) RefundFloater(r, floater) else null
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
