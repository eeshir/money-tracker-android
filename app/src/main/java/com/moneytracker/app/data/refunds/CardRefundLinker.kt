package com.moneytracker.app.data.refunds

import com.moneytracker.app.data.repository.RefundLinkRepository
import com.moneytracker.app.data.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns merchant refunds on credit cards into linked refunds, so they lower the spend of the
 * purchase they reverse instead of sitting as plain income. A refund with no matching purchase
 * is left as it is. Safe to run any number of times: a refund that is already handled is skipped.
 */
@Singleton
class CardRefundLinker @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val refundLinks: RefundLinkRepository
) {
    private val lock = Mutex()

    /** Links every waiting card refund; returns how many were linked. */
    suspend fun linkAll(): Int = lock.withLock {
        withContext(Dispatchers.IO) {
            val all = transactionRepository.getTransactionsBetweenDatesList(
                LocalDateTime.now().minusDays(400), LocalDateTime.now().plusDays(1)
            )
            val waiting = all.filter(CardRefundMatcher::isUnlinkedMerchantRefund).sortedBy { it.dateTime }
            if (waiting.isEmpty()) return@withContext 0
            val roomUsed = HashMap<Long, BigDecimal>()
            var linked = 0
            for (refund in waiting) {
                val requests = CardRefundMatcher.plan(refund, all, roomUsed)
                if (requests.isEmpty()) continue
                refundLinks.beginLinking(refund.id) ?: continue
                refundLinks.setLinks(refund.id, requests)
                linked++
            }
            linked
        }
    }
}
