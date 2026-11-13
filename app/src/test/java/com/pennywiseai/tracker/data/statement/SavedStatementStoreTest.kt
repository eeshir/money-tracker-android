package com.pennywiseai.tracker.data.statement

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SavedStatementStoreTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 200 * day

    private fun saved(
        id: String,
        account: String? = null,
        statementDay: Long = 0,
        emailDate: Long = 0,
        imported: Boolean = false,
        group: String? = null,
        gmail: Boolean = true,
        unsupported: Boolean = false
    ) = SavedStatement(
        id = id, label = id, bankName = "Bank", accountKey = account, statementDay = statementDay,
        emailDate = emailDate, importedAt = if (imported) 1L else 0L, groupKey = group,
        gmailKey = if (gmail) "g-$id" else null, unsupported = unsupported
    )

    @Test
    fun `the same PDF always gets the same id and different PDFs differ`() {
        val a = byteArrayOf(1, 2, 3, 4)
        assertEquals(SavedStatementStore.idOf(a), SavedStatementStore.idOf(a.copyOf()))
        assertNotEquals(SavedStatementStore.idOf(a), SavedStatementStore.idOf(byteArrayOf(1, 2, 3, 5)))
        assertEquals(16, SavedStatementStore.idOf(a).length)
    }

    @Test
    fun `a bank and card share one password entry regardless of case or spacing`() {
        assertEquals(
            StatementPasswordStore.entryKey("HDFC Bank", "1234"),
            StatementPasswordStore.entryKey("  hdfc bank ", "1234")
        )
        assertNotEquals(
            StatementPasswordStore.entryKey("HDFC Bank", "1234"),
            StatementPasswordStore.entryKey("HDFC Bank", "5678")
        )
        assertNotEquals(
            StatementPasswordStore.entryKey("HDFC Bank", "1234"),
            StatementPasswordStore.entryKey("HDFC Bank", null)
        )
    }

    @Test
    fun `saved statement metadata round-trips and tolerates fields it does not know`() {
        val statement = SavedStatement("abc123", "Sample Bank ••1234 · 5 Oct 2026", "Sample Bank", "1234", 42L)
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(statement, json.decodeFromString<SavedStatement>(json.encodeToString(statement)))
        assertEquals(
            SavedStatement("x", "label", "Bank"),
            json.decodeFromString<SavedStatement>("""{"id":"x","label":"label","bankName":"Bank","future":true}""")
        )
    }

    // --- grouping locked statements ---

    @Test
    fun `one sender's monthly statements share a group however the month and year are written`() {
        val october = SavedStatementStore.groupKeyOf("HDFC Bank Cards <Emailstatements.cards@hdfcbank.net>", "Your HDFC Bank - MoneyBack+ Credit Card Statement - October-2026")
        val september = SavedStatementStore.groupKeyOf("HDFC Bank Cards <Emailstatements.cards@hdfcbank.net>", "Your HDFC Bank - MoneyBack+ Credit Card Statement - September-2026")
        val otherCard = SavedStatementStore.groupKeyOf("HDFC Bank Cards <Emailstatements.cards@hdfcbank.net>", "Your HDFC Bank - Tata Neu Plus Credit Card Statement - October-2026")
        assertEquals(october, september)
        assertNotEquals(october, otherCard)
    }

    @Test
    fun `different senders never share a group`() {
        assertNotEquals(
            SavedStatementStore.groupKeyOf("A <a@bank-one.com>", "Your statement"),
            SavedStatementStore.groupKeyOf("B <b@bank-two.com>", "Your statement")
        )
    }

    // --- what is offered for import ---

    @Test
    fun `only the newest statement of each known account is offered, newest first`() {
        val statements = listOf(
            saved("hsbc-aug", "bank-c|5555", statementDay = 100, emailDate = 100 * day),
            saved("hsbc-oct", "bank-c|5555", statementDay = 160, emailDate = 160 * day),
            saved("hsbc-sep", "bank-c|5555", statementDay = 130, emailDate = 130 * day),
            saved("hdfc-oct", "bank-d|6666", statementDay = 170, emailDate = 170 * day)
        )
        assertEquals(listOf("hdfc-oct", "hsbc-oct"), SavedStatementStore.latestWaiting(statements).map { it.id })
    }

    @Test
    fun `a card whose newest statement is already imported has nothing waiting`() {
        val statements = listOf(
            saved("sep", "bank-c|5555", statementDay = 130, emailDate = 130 * day),
            saved("oct", "bank-c|5555", statementDay = 160, emailDate = 160 * day, imported = true)
        )
        assertEquals(emptyList<String>(), SavedStatementStore.latestWaiting(statements).map { it.id })
    }

    @Test
    fun `locked statements from one sender and subject show only the latest round`() {
        val g = "sbi|e-account statement"
        val statements = listOf(
            saved("aug", group = g, emailDate = 100 * day),
            saved("sep", group = g, emailDate = 130 * day),
            saved("oct", group = g, emailDate = 160 * day)
        )
        assertEquals(listOf("oct"), SavedStatementStore.latestWaiting(statements).map { it.id })
    }

    @Test
    fun `two cards sharing a sender and subject are both kept when their statements arrive together`() {
        val g = "bank|credit card statement"
        val statements = listOf(
            saved("card-a-sep", group = g, emailDate = 130 * day),
            saved("card-a-oct", group = g, emailDate = 160 * day),
            saved("card-b-oct", group = g, emailDate = 161 * day)
        )
        assertEquals(setOf("card-a-oct", "card-b-oct"), SavedStatementStore.latestWaiting(statements).map { it.id }.toSet())
    }

    @Test
    fun `unsupported and hand-picked statements are not offered`() {
        val statements = listOf(
            saved("sbi", account = null, emailDate = 160 * day, unsupported = true),
            saved("picked", account = "bank-e|7777", statementDay = 160, gmail = false)
        )
        assertEquals(emptyList<String>(), SavedStatementStore.latestWaiting(statements).map { it.id })
    }

    // --- what may be deleted ---

    @Test
    fun `only imported statements that a newer one replaces can be deleted`() {
        val statements = listOf(
            saved("old-imported", "bank-c|5555", statementDay = 100, imported = true),
            saved("old-waiting", "bank-c|5555", statementDay = 110),
            saved("newest", "bank-c|5555", statementDay = 130),
            saved("alone", "bank-d|6666", statementDay = 90, imported = true)
        )
        assertEquals(listOf("old-imported"), SavedStatementStore.supersededIds(statements))
    }

    @Test
    fun `ordering puts the latest first by arrival, then statement date, then save time`() {
        val a = saved("a", emailDate = 10 * day)
        val b = saved("b", emailDate = 20 * day)
        val c = saved("c", statementDay = 15).copy(emailDate = 0)
        assertEquals(listOf("b", "c", "a"), listOf(a, b, c).sortedByDescending { it.sortMillis }.map { it.id })
    }

    // --- where the search starts ---

    @Test
    fun `with nothing held the search goes back the full period`() {
        val cutoff = SavedStatementStore.fetchCutoff(emptyList(), now, months = 3)
        // About three months: well over two, well under four.
        assertEquals(true, now - cutoff in (60 * day)..(120 * day))
    }

    @Test
    fun `the search starts a day before the least caught-up account`() {
        val statements = listOf(
            saved("a", "bank|1111", statementDay = 1, emailDate = now - 5 * day),
            saved("b", "bank|2222", statementDay = 1, emailDate = now - 20 * day),
            saved("a-older", "bank|1111", statementDay = 0, emailDate = now - 40 * day)
        )
        assertEquals(now - 21 * day, SavedStatementStore.fetchCutoff(statements, now, months = 3))
    }

    @Test
    fun `an account that has not had a statement for ages never reaches past the period`() {
        val statements = listOf(saved("stale", "bank|9999", statementDay = 1, emailDate = now - 150 * day))
        val floor = SavedStatementStore.fetchCutoff(emptyList(), now, months = 3)
        assertEquals(floor, SavedStatementStore.fetchCutoff(statements, now, months = 3))
    }
}
