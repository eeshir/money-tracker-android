package com.moneytracker.app.data.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moneytracker.app.data.database.entity.BudgetImpactType
import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionSplitEntity
import com.moneytracker.app.data.database.entity.TransactionType
import com.moneytracker.app.data.refunds.RefundLinks
import com.moneytracker.app.data.repository.RefundLinkRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Refund links against real SQLite: the 67 to 68 migration, the live-link query, the cascades, and
 * RefundLinkRepository keeping the stored copies in step inside real Room transactions.
 */
@RunWith(AndroidJUnit4::class)
class RefundLinkDatabaseTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, PennyWiseDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    private lateinit var db: PennyWiseDatabase
    private lateinit var repo: RefundLinkRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, PennyWiseDatabase::class.java).build()
        repo = RefundLinkRepository(db, db.refundAllocationDao(), db.transactionDao(), db.transactionSplitDao())
    }

    @After
    fun tearDown() = db.close()

    private fun d(v: String) = BigDecimal(v)

    private suspend fun expense(amount: String, hash: String, category: String = "Rent") = db.transactionDao().insertTransaction(
        TransactionEntity(
            amount = d(amount), merchantName = "Landlord", category = category, transactionType = TransactionType.EXPENSE,
            dateTime = LocalDateTime.of(2026, 10, 3, 12, 0), transactionHash = hash, currency = "INR"
        )
    )

    private suspend fun refund(amount: String, hash: String, category: String? = null) = db.transactionDao().insertTransaction(
        TransactionEntity(
            amount = d(amount), merchantName = "Flatmate", category = "Others", transactionType = TransactionType.INCOME,
            dateTime = LocalDateTime.of(2026, 10, 5, 12, 0), transactionHash = hash, currency = "INR",
            budgetImpactType = BudgetImpactType.DEDUCT_SPENT, budgetCategory = category
        )
    )

    private suspend fun stored(id: Long) = db.transactionDao().getTransactionById(id)!!.refundedTotal

    @Test
    fun migration67To68AddsTheTableAndColumnsAndKeepsRows() {
        val name = "refund-migration-test"
        helper.createDatabase(name, 67).apply {
            execSQL(
                """INSERT INTO transactions (id, amount, merchant_name, category, transaction_type, date_time,
                transaction_hash, is_recurring, created_at, updated_at) VALUES
                (1, '1000', 'Landlord', 'Rent', 'EXPENSE', '2026-10-01T20:00:00', 'synthetic', 0,
                '2026-10-01T20:00:00', '2026-10-01T20:00:00')"""
            )
            close()
        }
        val migrated = helper.runMigrationsAndValidate(name, 68, true)
        migrated.query("SELECT amount, refunded_total FROM transactions WHERE id = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals("1000", it.getString(0))
            assertTrue(it.isNull(1))
        }
        migrated.query("SELECT COUNT(*) FROM refund_allocations").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
    }

    @Test
    fun linkingStoresTheRefundedTotalOnTheExpense() = runBlocking {
        val e = expense("30000", "e1"); val r = refund("20000", "r1")
        val out = repo.setLinks(r, listOf(RefundLinks.Request(e, null, d("20000"))))
        assertEquals(d("20000"), out.total)
        assertEquals(0, d("20000").compareTo(stored(e)))
    }

    @Test
    fun linkingDropsTheRefundsOwnCategory() = runBlocking {
        val e = expense("1000", "e1"); val r = refund("400", "r1", category = "Rent")
        // a refund with a category is not link-managed, but setLinks still takes it over
        repo.setLinks(r, listOf(RefundLinks.Request(e, null, d("400"))))
        assertNull(db.transactionDao().getTransactionById(r)!!.budgetCategory)
        assertEquals(0, d("400").compareTo(stored(e)))
    }

    @Test
    fun unlinkingClearsTheStoredTotal() = runBlocking {
        val e = expense("1000", "e1"); val r = refund("400", "r1")
        repo.setLinks(r, listOf(RefundLinks.Request(e, null, d("400"))))
        repo.setLinks(r, emptyList())
        assertNull(stored(e))
    }

    @Test
    fun relinkingToAnotherExpenseMovesTheMoney() = runBlocking {
        val a = expense("1000", "e1"); val b = expense("1000", "e2"); val r = refund("600", "r1")
        repo.setLinks(r, listOf(RefundLinks.Request(a, null, d("600"))))
        repo.setLinks(r, listOf(RefundLinks.Request(b, null, d("600"))))
        assertNull(stored(a))
        assertEquals(0, d("600").compareTo(stored(b)))
    }

    @Test
    fun aSplitExpenseGetsTheRefundOnTheNamedPartOnly() = runBlocking {
        val e = expense("30000", "e1")
        db.transactionSplitDao().insertSplits(
            listOf(
                TransactionSplitEntity(transactionId = e, category = "Rent", amount = d("28000")),
                TransactionSplitEntity(transactionId = e, category = "Food", amount = d("2000"))
            )
        )
        val r = refund("18000", "r1")
        repo.setLinks(r, listOf(RefundLinks.Request(e, "Rent", d("18000"))))
        val parts = db.transactionSplitDao().getSplitsForTransactionSync(e)
        assertEquals(0, d("18000").compareTo(parts.first { it.category == "Rent" }.refundedAmount))
        assertNull(parts.first { it.category == "Food" }.refundedAmount)
    }

    @Test
    fun softDeletingTheRefundLetsGoOfTheExpenseAndRestoringTakesItBack() = runBlocking {
        val e = expense("1000", "e1"); val r = refund("400", "r1")
        repo.setLinks(r, listOf(RefundLinks.Request(e, null, d("400"))))
        db.transactionDao().softDeleteTransaction(r)
        repo.onChanged(r)
        assertNull(stored(e))
        val deleted = db.transactionDao().getTransactionById(r)!!
        db.transactionDao().updateTransaction(deleted.copy(isDeleted = false))
        repo.onChanged(r)
        assertEquals(0, d("400").compareTo(stored(e)))
    }

    @Test
    fun hardDeletingTheRefundOrTheExpenseCascadesTheLinks() = runBlocking {
        val e = expense("1000", "e1"); val r = refund("400", "r1")
        repo.setLinks(r, listOf(RefundLinks.Request(e, null, d("400"))))
        db.transactionDao().deleteTransactionById(r)
        assertTrue(db.refundAllocationDao().getAll().isEmpty())
        repo.refreshAll()
        assertNull(stored(e))

        val r2 = refund("300", "r2")
        repo.setLinks(r2, listOf(RefundLinks.Request(e, null, d("300"))))
        db.transactionDao().deleteTransactionById(e)
        assertTrue(db.refundAllocationDao().getAll().isEmpty())
    }

    @Test
    fun aStaleCopyOfTheExpenseCannotEraseTheStoredTotalForLong() = runBlocking {
        val e = expense("1000", "e1"); val r = refund("400", "r1")
        repo.setLinks(r, listOf(RefundLinks.Request(e, null, d("400"))))
        val stale = db.transactionDao().getTransactionById(e)!!.copy(refundedTotal = null)
        db.transactionDao().updateTransaction(stale)
        assertNull(stored(e))
        repo.refreshAll()
        assertEquals(0, d("400").compareTo(stored(e)))
    }

    @Test
    fun givingALinkedRefundACategoryMakesItsLinksDormant() = runBlocking {
        val e = expense("1000", "e1"); val r = refund("400", "r1")
        repo.setLinks(r, listOf(RefundLinks.Request(e, null, d("400"))))
        val withCategory = db.transactionDao().getTransactionById(r)!!.copy(budgetCategory = "Rent")
        db.transactionDao().updateTransaction(withCategory)
        repo.onChanged(r)
        assertNull(stored(e))
    }

    @Test
    fun twoRefundsCannotTogetherPassTheExpense() = runBlocking {
        val e = expense("1000", "e1"); val r1 = refund("800", "r1"); val r2 = refund("800", "r2")
        repo.setLinks(r1, listOf(RefundLinks.Request(e, null, d("800"))))
        val second = repo.setLinks(r2, listOf(RefundLinks.Request(e, null, d("800"))))
        assertEquals(0, d("200").compareTo(second.total))
        assertEquals(0, d("1000").compareTo(stored(e)))
    }

    @Test
    fun aNonRefundCannotHaveLinks() = runBlocking {
        val e = expense("1000", "e1"); val notRefund = expense("50", "x1")
        val out = repo.setLinks(notRefund, listOf(RefundLinks.Request(e, null, d("50"))))
        assertTrue(out.accepted.isEmpty())
        assertNull(stored(e))
    }

    private suspend fun income(amount: String, hash: String, impact: BudgetImpactType?, category: String? = null) = db.transactionDao().insertTransaction(
        TransactionEntity(
            amount = d(amount), merchantName = "Friend", category = "Others", transactionType = TransactionType.INCOME,
            dateTime = LocalDateTime.of(2026, 10, 5, 12, 0), transactionHash = hash, currency = "INR",
            budgetImpactType = impact, budgetCategory = category
        )
    )

    @Test
    fun beginLinkingMakesAPlainIncomeARefundAndRestoreGivesItBack() = runBlocking {
        val id = income("500", "i1", impact = null)
        val before = repo.beginLinking(id)
        assertNull(before!!.impact)
        assertEquals(BudgetImpactType.DEDUCT_SPENT, db.transactionDao().getTransactionById(id)!!.budgetImpactType)
        repo.restore(id, before)
        assertNull(db.transactionDao().getTransactionById(id)!!.budgetImpactType)
    }

    @Test
    fun restoreBringsBackAnExtraBudgetIncomeWithItsCategory() = runBlocking {
        val id = income("500", "i1", impact = BudgetImpactType.ADD_TO_LIMIT, category = "Food")
        val before = repo.beginLinking(id)!!
        assertNull(db.transactionDao().getTransactionById(id)!!.budgetCategory)
        repo.restore(id, before)
        val back = db.transactionDao().getTransactionById(id)!!
        assertEquals(BudgetImpactType.ADD_TO_LIMIT, back.budgetImpactType)
        assertEquals("Food", back.budgetCategory)
    }

    @Test
    fun beginLinkingLeavesAnExistingRefundAloneAndHasNothingToRestore() = runBlocking {
        val id = refund("500", "r1")
        assertNull(repo.beginLinking(id))
        assertEquals(BudgetImpactType.DEDUCT_SPENT, db.transactionDao().getTransactionById(id)!!.budgetImpactType)
    }

    @Test
    fun beginLinkingRefusesAnExpense() = runBlocking {
        val id = expense("500", "e1")
        assertNull(repo.beginLinking(id))
        assertNull(db.transactionDao().getTransactionById(id)!!.budgetImpactType)
    }

    @Test
    fun moneyLinkedToADeletedExpenseIsAFloaterAgain() = runBlocking {
        val e = expense("1000", "e1"); val r = refund("400", "r1")
        repo.setLinks(r, listOf(RefundLinks.Request(e, null, d("400"))))
        assertEquals(1, db.refundAllocationDao().observeAll().first().size)
        db.transactionDao().softDeleteTransaction(e)
        assertTrue(db.refundAllocationDao().observeAll().first().isEmpty())
        assertTrue(db.refundAllocationDao().observeForRefund(r).first().isEmpty())
    }

    @Test
    fun theHomeListHasOnlyLinkManagedRefunds() = runBlocking {
        val floating = refund("400", "r1")
        refund("300", "r2", category = "Rent")
        income("200", "i1", impact = null)
        income("100", "i2", impact = BudgetImpactType.ADD_TO_LIMIT)
        val listed = db.transactionDao().observeLinkManagedRefunds().first()
        assertEquals(listOf(floating), listed.map { it.id })
    }

    @Test
    fun linkingFromTheExpenseSideStoresTheTotalAndPaysFromTwoRefunds() = runBlocking {
        val e = expense("30000", "e1"); val r1 = refund("10000", "r1"); val r2 = refund("8000", "r2")
        val problems = repo.setExpenseLinks(e, null, mapOf(r1 to d("10000"), r2 to d("8000")))
        assertTrue(problems.isEmpty())
        assertEquals(0, d("18000").compareTo(stored(e)))
    }

    @Test
    fun theExpenseSideKeepsTheRefundsLinksToOtherExpenses() = runBlocking {
        val a = expense("1000", "e1"); val b = expense("1000", "e2"); val r = refund("700", "r1")
        repo.setLinks(r, listOf(RefundLinks.Request(a, null, d("300"))))
        repo.setExpenseLinks(b, null, mapOf(r to d("400")))
        assertEquals(0, d("300").compareTo(stored(a)))
        assertEquals(0, d("400").compareTo(stored(b)))
    }

    @Test
    fun clearingFromTheExpenseSideRemovesOnlyThatLink() = runBlocking {
        val a = expense("1000", "e1"); val b = expense("1000", "e2"); val r = refund("700", "r1")
        repo.setLinks(r, listOf(RefundLinks.Request(a, null, d("300")), RefundLinks.Request(b, null, d("400"))))
        repo.setExpenseLinks(b, null, mapOf(r to BigDecimal.ZERO))
        assertEquals(0, d("300").compareTo(stored(a)))
        assertNull(stored(b))
    }

    @Test
    fun theExpenseSideChangesOnlyTheChosenPartOfASplitExpense() = runBlocking {
        val e = expense("30000", "e1")
        db.transactionSplitDao().insertSplits(
            listOf(
                TransactionSplitEntity(transactionId = e, category = "Rent", amount = d("28000")),
                TransactionSplitEntity(transactionId = e, category = "Food", amount = d("2000"))
            )
        )
        val r = refund("20000", "r1")
        repo.setExpenseLinks(e, "Rent", mapOf(r to d("18000")))
        repo.setExpenseLinks(e, "Food", mapOf(r to d("1500")))
        repo.setExpenseLinks(e, "Food", mapOf(r to BigDecimal.ZERO))
        val parts = db.transactionSplitDao().getSplitsForTransactionSync(e)
        assertEquals(0, d("18000").compareTo(parts.first { it.category == "Rent" }.refundedAmount))
        assertNull(parts.first { it.category == "Food" }.refundedAmount)
        assertEquals(0, d("18000").compareTo(stored(e)))
    }

    @Test
    fun theExpenseSideCannotPutMoreOnARefundThanItHas() = runBlocking {
        val a = expense("1000", "e1"); val b = expense("1000", "e2"); val r = refund("500", "r1")
        repo.setLinks(r, listOf(RefundLinks.Request(a, null, d("400"))))
        val problems = repo.setExpenseLinks(b, null, mapOf(r to d("400")))
        assertTrue(problems.isNotEmpty())
        assertEquals(0, d("100").compareTo(stored(b)))
        assertEquals(0, d("400").compareTo(stored(a)))
    }
}
