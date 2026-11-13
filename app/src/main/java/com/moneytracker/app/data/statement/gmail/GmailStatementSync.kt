package com.moneytracker.app.data.statement.gmail

import android.net.Uri
import com.moneytracker.app.data.statement.Identification
import com.moneytracker.app.data.statement.ImportStatementUseCase
import com.moneytracker.app.data.statement.SavedStatement
import com.moneytracker.app.data.statement.SavedStatementStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One Gmail fetch, end to end: look only as far back as needed, save every new
 * statement PDF the moment it is downloaded, work out which bank and card each
 * is for (with the remembered passwords — a still-locked one stays as it is),
 * and offer only the newest statement of each card.
 */
@Singleton
class GmailStatementSync @Inject constructor(
    private val client: GmailStatementClient,
    private val store: SavedStatementStore,
    private val importStatement: ImportStatementUseCase
) {
    /** Fetches new statements into the store. */
    suspend fun fetch(token: String, nowMillis: Long = System.currentTimeMillis(), backfill: Boolean = false) = withContext(Dispatchers.IO) {
        reidentifyPending()
        val months = GmailStatementClient.monthsBack(nowMillis)
        // A backfill ignores how caught up each card is and looks the whole way back.
        val since = if (backfill) java.time.Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).minusMonths(months.toLong()).toInstant().toEpochMilli()
        else SavedStatementStore.fetchCutoff(store.list(), nowMillis, months)
        val alreadyHave = store.list().mapNotNull { it.gmailKey }.toSet()
        for (statement in client.findStatements(token, since).filter { it.key !in alreadyHave }) {
            val file = client.download(token, statement)
            val saved = store.save(
                pdf = file.readBytes(),
                bankName = senderName(statement.from),
                cardLast4 = null,
                label = provisionalLabel(statement),
                gmailKey = statement.key,
                emailDate = statement.receivedAtMillis,
                groupKey = SavedStatementStore.groupKeyOf(statement.from, statement.subject)
            )
            record(saved.id, Uri.fromFile(file))
            // The kept copy is the one used from now on; the download cache can go.
            file.delete()
        }
        // Read each statement's reward total before older ones are cleared as superseded.
        try { importStatement.refreshStatementRewards(retry = true) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        store.dropSuperseded()
    }

    /**
     * Opens the statements that were still locked when fetched — a password learned
     * since (from opening one statement of the bank) unlocks its siblings.
     */
    suspend fun reidentifyPending() = withContext(Dispatchers.IO) {
        try {
            for (statement in store.list().filter { it.accountKey == null && !it.unsupported }) {
                record(statement.id, Uri.fromFile(store.openPlain(statement.id)))
            }
        } finally {
            store.closePlain()
        }
    }

    /** Every fetched statement not imported yet that can be read, oldest first (a backfill imports them all). */
    suspend fun allUnimported(): List<SavedStatement> = withContext(Dispatchers.IO) {
        store.list().filter { it.gmailKey != null && !it.unsupported && it.importedAt == 0L }.sortedBy { it.sortMillis }
    }

    /** Fetched statements still waiting to be imported: the newest of each card, newest first. */
    suspend fun waiting(): List<SavedStatement> = withContext(Dispatchers.IO) { SavedStatementStore.latestWaiting(store.list()) }

    private suspend fun record(id: String, uri: Uri) {
        when (val result = importStatement.identify(uri)) {
            is Identification.Known -> store.update(id) {
                it.copy(
                    label = result.who.label, bankName = result.who.bankName, cardLast4 = result.who.cardLast4,
                    accountKey = result.who.accountKey, statementDay = result.who.statementDay
                )
            }
            Identification.Unsupported -> store.update(id) { it.copy(unsupported = true) }
            Identification.Locked -> Unit
        }
    }

    private fun senderName(from: String): String =
        from.substringBefore('<').trim().trim('"').ifBlank { from.trim() }

    /** What a PDF is called until it can be opened: its email's subject and date. */
    private fun provisionalLabel(statement: GmailStatement): String {
        val day = Instant.ofEpochMilli(statement.receivedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        return "${statement.subject.ifBlank { statement.fileName }} · ${DATE.format(day)}"
    }

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
    }
}
