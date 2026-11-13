package com.moneytracker.app.data.backup

import com.moneytracker.app.data.database.entity.RefundAllocationEntity
import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionSplitEntity
import com.moneytracker.app.data.database.entity.TransactionType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

/** Refund links in the backup file: they round-trip, and a backup made before they existed still reads. */
class RefundLinkBackupTest {

    @Test
    fun linksAndTheStoredCopiesSurviveTheBackupFormat() {
        val link = RefundAllocationEntity(
            id = 3, refundId = 11, expenseId = 7, category = "Rent", amount = BigDecimal("18000.50"),
            createdAt = LocalDateTime.of(2026, 11, 3, 10, 0)
        )
        val expense = TransactionEntity(
            id = 7, amount = BigDecimal("30000"), merchantName = "Landlord", category = "Rent",
            transactionType = TransactionType.EXPENSE, dateTime = LocalDateTime.of(2026, 10, 3, 12, 0),
            transactionHash = "synthetic-refund-expense", refundedTotal = BigDecimal("18000.50")
        )
        val part = TransactionSplitEntity(
            id = 1, transactionId = 7, category = "Rent", amount = BigDecimal("28000"), refundedAmount = BigDecimal("18000.50")
        )
        assertEquals(link, backupJson.decodeFromString<RefundAllocationEntity>(backupJson.encodeToString(link)))
        assertEquals(expense, backupJson.decodeFromString<TransactionEntity>(backupJson.encodeToString(expense)))
        assertEquals(part, backupJson.decodeFromString<TransactionSplitEntity>(backupJson.encodeToString(part)))
    }

    @Test
    fun aSnapshotWithLinksKeepsThem() {
        val link = RefundAllocationEntity(refundId = 11, expenseId = 7, category = null, amount = BigDecimal("500"))
        val snapshot = DatabaseSnapshot(refundAllocations = listOf(link))
        val back = backupJson.decodeFromString<DatabaseSnapshot>(backupJson.encodeToString(snapshot))
        assertEquals(1, back.refundAllocations.size)
        assertEquals(BigDecimal("500"), back.refundAllocations.single().amount)
        assertNull(back.refundAllocations.single().category)
    }

    @Test
    fun aBackupFromBeforeRefundLinksStillReads() {
        // An old file has no refund_allocations and no refunded_total / refunded_amount: all default.
        val old = """{"transactions":[],"transaction_splits":[{"id":1,"transactionId":4,"category":"Food","amount":"20","createdAt":"2026-10-01T10:00:00"}]}"""
        val snapshot = backupJson.decodeFromString<DatabaseSnapshot>(old)
        assertTrue(snapshot.refundAllocations.isEmpty())
        assertNull(snapshot.transactionSplits.single().refundedAmount)
    }
}
