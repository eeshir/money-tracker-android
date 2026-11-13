package com.pennywiseai.tracker.data.statement

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** A statement PDF kept on the phone so it can be imported again without fetching it. */
@Serializable
data class SavedStatement(
    val id: String,
    val label: String,
    val bankName: String,
    val cardLast4: String? = null,
    val savedAt: Long = 0L,
    /** The Gmail attachment it came from; null for a file picked by hand. */
    val gmailKey: String? = null,
    /** When that email arrived (epoch ms); 0 for a hand-picked file. */
    val emailDate: Long = 0L,
    /** Which account it's for ([StatementPasswordStore.entryKey]); null while still locked or unreadable. */
    val accountKey: String? = null,
    /** The statement's own date (epoch day); 0 until it has been read. */
    val statementDay: Long = 0L,
    /** When it was imported (epoch ms); 0 if not yet. */
    val importedAt: Long = 0L,
    /** When its balance and limit were applied to the account (epoch ms); 0 if not. */
    val appliedAt: Long = 0L,
    /**
     * Sender + subject with the dates and numbers taken out, so a locked statement
     * can still be told apart from other banks' and matched with the same
     * sender's earlier ones ([groupKeyOf]).
     */
    val groupKey: String? = null,
    /** Opened fine, but PennyWise has no reader for this kind of statement yet. */
    val unsupported: Boolean = false
) {
    /** What orders statements, newest first: when it arrived, else its own date, else when it was saved. */
    val sortMillis: Long
        get() = emailDate.takeIf { it > 0 } ?: (statementDay * DAY_MILLIS).takeIf { it > 0 } ?: savedAt

    /** Statements for the same account (or, while locked, from the same sender and subject). */
    val groupId: String get() = accountKey ?: groupKey ?: id
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

/**
 * The statement PDFs that read successfully, kept exactly as the bank sent
 * them — still locked, so a copy is no more exposed than the original email.
 * They live in the app's private files (not cache, which the system may clear),
 * one `<id>.pdf` plus a `<id>.json` describing it. The id is a hash of the
 * file, so saving the same statement twice keeps one copy.
 */
@Singleton
class SavedStatementStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: StatementFileCipher
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir get() = File(context.filesDir, DIR).apply { mkdirs() }

    /**
     * Saves [pdf]. A copy already saved keeps what is known about it: [savedAt],
     * [SavedStatement.importedAt], and any Gmail details or account the new call
     * doesn't supply.
     */
    @Synchronized
    fun save(
        pdf: ByteArray,
        bankName: String,
        cardLast4: String?,
        label: String,
        gmailKey: String? = null,
        emailDate: Long = 0L,
        accountKey: String? = null,
        statementDay: Long = 0L,
        groupKey: String? = null
    ): SavedStatement {
        val id = idOf(pdf)
        val old = list().firstOrNull { it.id == id }
        val statement = SavedStatement(
            id = id,
            label = label,
            bankName = bankName,
            cardLast4 = cardLast4,
            savedAt = old?.savedAt ?: System.currentTimeMillis(),
            gmailKey = gmailKey ?: old?.gmailKey,
            emailDate = emailDate.takeIf { it != 0L } ?: old?.emailDate ?: 0L,
            accountKey = accountKey ?: old?.accountKey,
            statementDay = statementDay.takeIf { it != 0L } ?: old?.statementDay ?: 0L,
            importedAt = old?.importedAt ?: 0L,
            appliedAt = old?.appliedAt ?: 0L,
            groupKey = groupKey ?: old?.groupKey,
            unsupported = old?.unsupported ?: false
        )
        val target = file(id)
        // A copy saved before encryption existed is plain: write it again, encrypted.
        if (!target.exists() || !StatementFileCipher.isEncrypted(target.readBytes())) target.writeBytes(cipher.encrypt(pdf))
        write(statement)
        return statement
    }

    /** Changes the description of a saved statement (what reading it taught us). */
    @Synchronized
    fun update(id: String, change: (SavedStatement) -> SavedStatement) {
        val current = list().firstOrNull { it.id == id } ?: return
        write(change(current).copy(id = id))
    }

    fun markImported(id: String) = update(id) { it.copy(importedAt = System.currentTimeMillis()) }

    /** Marks the statement of [accountKey] dated [statementDay] as applied to its account. */
    fun markApplied(accountKey: String, statementDay: Long) {
        list().filter { it.accountKey == accountKey && it.statementDay == statementDay }
            .forEach { statement -> update(statement.id) { it.copy(appliedAt = System.currentTimeMillis()) } }
    }

    /** Newest first. Entries whose PDF is gone are dropped. */
    fun list(): List<SavedStatement> {
        encryptLegacyOnce()
        return readAll()
    }

    @Volatile private var legacyChecked = false

    /** Files saved before encryption existed are encrypted in place, once per launch. */
    @Synchronized
    private fun encryptLegacyOnce() {
        if (legacyChecked) return
        legacyChecked = true
        dir.listFiles().orEmpty().forEach { f ->
            runCatching {
                val bytes = f.readBytes()
                if (!StatementFileCipher.isEncrypted(bytes)) f.writeBytes(cipher.encrypt(bytes))
            }
        }
    }

    private fun readAll(): List<SavedStatement> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { json.decodeFromString<SavedStatement>(String(cipher.decrypt(it.readBytes()), Charsets.UTF_8)) }.getOrNull() }
            .filter { file(it.id).exists() }
            .sortedByDescending { it.sortMillis }

    /** The stored (encrypted) file; use [openPlain] to read the PDF. */
    fun file(id: String): File = File(dir, "$id.pdf")

    /**
     * A readable copy of the statement in the cache, for the PDF reader, which opens
     * files by path. Still locked by the bank's own password. Earlier copies are
     * removed first, and the system clears the cache, so at most one lingers.
     */
    @Synchronized
    fun openPlain(id: String): File {
        val folder = File(context.cacheDir, OPEN_DIR).apply { mkdirs() }
        folder.listFiles()?.forEach { if (it.name != "$id.pdf") it.delete() }
        return File(folder, "$id.pdf").also { it.writeBytes(cipher.decrypt(file(id).readBytes())) }
    }

    /** Removes the readable copy made by [openPlain]. */
    fun closePlain() {
        File(context.cacheDir, OPEN_DIR).deleteRecursively()
    }

    fun delete(id: String) {
        file(id).delete()
        File(dir, "$id.json").delete()
    }

    /** Deletes the already-imported statements a newer one for the same account replaces. Returns the ids removed. */
    @Synchronized
    fun dropSuperseded(): List<String> = supersededIds(list()).also { it.forEach(::delete) }

    private fun write(statement: SavedStatement) {
        File(dir, "${statement.id}.json").writeBytes(cipher.encrypt(json.encodeToString(statement).toByteArray(Charsets.UTF_8)))
    }

    companion object {
        private const val DIR = "statements"
        private const val OPEN_DIR = "statement-open"

        /** First 16 hex digits of the PDF's SHA-256. */
        internal fun idOf(pdf: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(pdf).take(8).joinToString("") { "%02x".format(it) }

        private val MONTH_WORDS = Regex(
            """\b(jan(uary)?|feb(ruary)?|mar(ch)?|apr(il)?|may|jun(e)?|jul(y)?|aug(ust)?|sep(t(ember)?)?|oct(ober)?|nov(ember)?|dec(ember)?)\b"""
        )

        /**
         * Groups a locked statement with the same sender's others: the sender's address
         * plus the subject without months, years or other numbers ("… Statement -
         * October-2026" and "… September-2026" become one group).
         */
        internal fun groupKeyOf(fromHeader: String, subject: String): String {
            val address = Regex("""<([^>]+)>""").find(fromHeader)?.groupValues?.get(1) ?: fromHeader
            val words = subject.lowercase()
                .replace(MONTH_WORDS, " ")
                .replace(Regex("""[^a-z]+"""), " ")
                .trim()
            return "${address.trim().lowercase()}|$words"
        }

        /**
         * Statements that can go: already imported, and a newer one for the same
         * account exists. One that hasn't been imported is never dropped — it may
         * hold transactions no SMS caught. Ones with no account yet group by their
         * sender and subject.
         */
        internal fun supersededIds(statements: List<SavedStatement>): List<String> =
            statements.groupBy { it.groupId }
                .values
                .flatMap { group ->
                    val newest = group.maxWith(compareBy<SavedStatement> { it.statementDay }.thenBy { it.sortMillis })
                    group.filter { it.id != newest.id && it.importedAt > 0 }.map { it.id }
                }

        /** Statements that arrived within this long of the newest of their group count as the same round. */
        private const val SAME_ROUND_MILLIS = 10 * DAY_MILLIS

        /**
         * Fetched statements still waiting to be imported: of each account, only the
         * newest — and none if that newest is already imported. A group whose account
         * isn't known yet (locked) keeps everything within [SAME_ROUND_MILLIS] of its
         * newest, since two cards from one sender can share a subject. Newest first;
         * statements PennyWise can't read are left out.
         */
        internal fun latestWaiting(statements: List<SavedStatement>): List<SavedStatement> =
            statements.filter { it.gmailKey != null && !it.unsupported }
                .groupBy { it.groupId }
                .values
                .flatMap { group ->
                    val newest = group.maxWith(compareBy<SavedStatement> { it.statementDay }.thenBy { it.sortMillis })
                    group.filter { candidate ->
                        candidate.importedAt == 0L && if (candidate.accountKey != null) {
                            candidate.id == newest.id
                        } else {
                            newest.sortMillis - candidate.sortMillis <= SAME_ROUND_MILLIS
                        }
                    }
                }
                .sortedByDescending { it.sortMillis }

        /**
         * Where the next Gmail search starts: no further back than [months] months,
         * and no earlier than needed. Per account the newest statement already held
         * says how far that account is caught up; the search goes back to the
         * *least* caught-up account (a day earlier, to be safe), so no account's
         * newer statement is missed. Nothing held yet: the full [months] months.
         */
        internal fun fetchCutoff(statements: List<SavedStatement>, nowMillis: Long, months: Int): Long {
            val floor = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).minusMonths(months.toLong()).toInstant().toEpochMilli()
            val caughtUp = statements.filter { it.accountKey != null && it.emailDate > 0 }
                .groupBy { it.accountKey }
                .values
                .map { group -> group.maxOf { it.emailDate } }
            if (caughtUp.isEmpty()) return floor
            return maxOf(floor, caughtUp.min() - DAY_MILLIS)
        }
    }
}
