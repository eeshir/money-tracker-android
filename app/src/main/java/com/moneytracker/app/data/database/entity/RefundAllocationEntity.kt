package com.moneytracker.app.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.time.LocalDateTime
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Part of a refund (an INCOME marked "Refund") that pays back part of one expense: ₹5,000 of the flatmates'
 * ₹20,000 goes to the rent. The refund's amount not allocated anywhere is its "floater" and counts for nothing
 * until it is linked.
 *
 * [category] names which category part of a split expense this lands on; null means the expense as a whole
 * (or its only category). It is a name, not a split id, because editing a split rewrites its rows.
 *
 * The allocations are the source of truth; [TransactionEntity.refundedTotal] and
 * [TransactionSplitEntity.refundedAmount] are copies kept in step by RefundLinkRepository so that
 * countedAmount() needs no join.
 */
@Entity(
    tableName = "refund_allocations",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["refund_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["expense_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("refund_id"), Index("expense_id")]
)
@Serializable
data class RefundAllocationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "refund_id")
    val refundId: Long = 0,

    @ColumnInfo(name = "expense_id")
    val expenseId: Long = 0,

    @ColumnInfo(name = "category", defaultValue = "NULL")
    val category: String? = null,

    @Contextual
    val amount: BigDecimal = BigDecimal.ZERO,

    @ColumnInfo(name = "created_at")
    @Contextual
    val createdAt: LocalDateTime = LocalDateTime.now()
)
