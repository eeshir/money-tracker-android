package com.pennywiseai.tracker.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.pennywiseai.tracker.data.database.entity.RefundAllocationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RefundAllocationDao {

    @Query("SELECT * FROM refund_allocations ORDER BY id ASC")
    suspend fun getAll(): List<RefundAllocationEntity>

    /** Live: allocations that sit on an expense that still exists, so money linked to a deleted expense is a floater again. */
    @Query("SELECT a.* FROM refund_allocations a JOIN transactions e ON e.id = a.expense_id WHERE e.is_deleted = 0 ORDER BY a.id ASC")
    fun observeAll(): Flow<List<RefundAllocationEntity>>

    @Query("SELECT a.* FROM refund_allocations a JOIN transactions e ON e.id = a.expense_id WHERE a.refund_id = :refundId AND e.is_deleted = 0 ORDER BY a.id ASC")
    fun observeForRefund(refundId: Long): Flow<List<RefundAllocationEntity>>

    @Query("SELECT * FROM refund_allocations WHERE refund_id = :refundId ORDER BY id ASC")
    suspend fun getForRefund(refundId: Long): List<RefundAllocationEntity>

    @Query("SELECT * FROM refund_allocations WHERE expense_id = :expenseId ORDER BY id ASC")
    fun observeForExpense(expenseId: Long): Flow<List<RefundAllocationEntity>>

    @Query("SELECT * FROM refund_allocations WHERE expense_id = :expenseId ORDER BY id ASC")
    suspend fun getForExpense(expenseId: Long): List<RefundAllocationEntity>

    @Insert
    suspend fun insert(allocation: RefundAllocationEntity): Long

    @Insert
    suspend fun insertAll(allocations: List<RefundAllocationEntity>)

    @Query("DELETE FROM refund_allocations WHERE refund_id = :refundId")
    suspend fun deleteForRefund(refundId: Long)

    @Query("DELETE FROM refund_allocations")
    suspend fun deleteAll()

    /** The allocations on [expenseId] that still count: their refund exists, is not deleted, is still a refund and has no category of its own (a refund counts through its links or its category, never both). */
    @Query("""
        SELECT a.* FROM refund_allocations a JOIN transactions r ON r.id = a.refund_id
        WHERE a.expense_id = :expenseId AND r.is_deleted = 0 AND r.transaction_type = 'INCOME'
        AND r.budget_impact_type = 'DEDUCT_SPENT' AND r.budget_category IS NULL ORDER BY a.id ASC
    """)
    suspend fun getLiveForExpense(expenseId: Long): List<RefundAllocationEntity>

    /** Every allocation that still counts (see [getLiveForExpense]). */
    @Query("""
        SELECT a.* FROM refund_allocations a JOIN transactions r ON r.id = a.refund_id
        WHERE r.is_deleted = 0 AND r.transaction_type = 'INCOME'
        AND r.budget_impact_type = 'DEDUCT_SPENT' AND r.budget_category IS NULL ORDER BY a.id ASC
    """)
    suspend fun getAllLive(): List<RefundAllocationEntity>

    /** Refunds [expenseId]-style: the expenses a refund has allocations on. */
    @Query("SELECT DISTINCT expense_id FROM refund_allocations WHERE refund_id = :refundId")
    suspend fun expenseIdsForRefund(refundId: Long): List<Long>

    /** The refunds that have allocations on [expenseId]. */
    @Query("SELECT DISTINCT refund_id FROM refund_allocations WHERE expense_id = :expenseId")
    suspend fun refundIdsForExpense(expenseId: Long): List<Long>

    /** Expenses that have at least one allocation, to refresh their stored totals. */
    @Query("SELECT DISTINCT expense_id FROM refund_allocations")
    suspend fun linkedExpenseIds(): List<Long>

    /** Expenses whose stored refund totals are non-zero, which must be reset when nothing links to them any more. */
    @Query("SELECT id FROM transactions WHERE refunded_total IS NOT NULL")
    suspend fun expenseIdsWithStoredTotal(): List<Long>
}
