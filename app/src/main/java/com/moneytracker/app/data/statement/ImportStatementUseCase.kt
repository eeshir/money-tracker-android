package com.moneytracker.app.data.statement

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.moneytracker.parser.core.ParsedTransaction
import com.moneytracker.app.R
import com.moneytracker.app.data.database.PennyWiseDatabase
import com.moneytracker.app.data.database.entity.AccountBalanceEntity
import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.preferences.UserPreferencesRepository
import com.moneytracker.app.data.repository.AccountBalanceRepository
import com.moneytracker.app.data.repository.TransactionRepository
import com.moneytracker.app.ui.UiText
import com.moneytracker.app.utils.CurrencyFormatter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

/**
 * Statement import in three steps (#13): read the file (a supported bank PDF,
 * or any bank's .xlsx/.csv through a column mapping), [preview] what it would
 * change without writing, then [commit] the rows the user kept and
 * [balanceCheck] the statement's closing balance against the account's.
 */
class ImportStatementUseCase @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val accountBalanceRepository: AccountBalanceRepository,
    private val cardRepository: com.moneytracker.app.data.repository.CardRepository,
    private val preferences: UserPreferencesRepository,
    private val passwordStore: StatementPasswordStore,
    private val savedStatementStore: SavedStatementStore,
    private val cardDueStore: CardDueStore,
    private val statementRewardStore: StatementRewardStore,
    private val ownPayeeMirror: com.moneytracker.app.data.payees.OwnPayeeMirror,
    private val database: PennyWiseDatabase,
    @ApplicationContext private val context: Context
) {
    sealed interface PdfRead {
        /** [cardSummary]: a card statement's summary block (what was owed, limits), when it has one. */
        data class Parsed(
            val transactions: List<ParsedTransaction>,
            val cardSummary: CardStatementSummary? = null,
            /** The kept copy of this PDF, so a successful import can mark it done. */
            val savedId: String? = null
        ) : PdfRead
        data class Error(val message: UiText) : PdfRead
        /** The statement PDF is locked; ask for its password ([wrongPassword]: the last one failed). */
        data class PasswordRequired(val wrongPassword: Boolean) : PdfRead
    }

    suspend fun readPdf(uri: Uri, password: String? = null): PdfRead = withContext(Dispatchers.IO) {
        try {
            val (text, usedPassword) = unlock(uri, password)
            val parser = PdfParserFactory.getParser(text)
            if (parser == null) {
                rememberUnsupported(usedPassword)
                return@withContext PdfRead.Error(UiText.Res(R.string.import_statement_error_unsupported))
            }
            val parsed = parser.parse(text)
            val summary = (parser as? CardStatementParser)?.summary(text)
            summary?.let(cardDueStore::record)
            recordRewards(text, summary, (parser as? CardStatementParser)?.rowRewards(text), (parser as? CardStatementParser)?.rowRewardDetails(text).orEmpty())
            if (parsed.isEmpty()) {
                rememberUnsupported(usedPassword)
                PdfRead.Error(UiText.Res(R.string.import_statement_error_no_transactions))
            } else {
                val savedId = remember(uri, usedPassword, parsed, summary)
                PdfRead.Parsed(parsed, summary, savedId)
            }
        } catch (e: PdfPasswordRequiredException) {
            PdfRead.PasswordRequired(e.wrongPassword)
        } catch (e: Exception) {
            // The exception's own text is English and technical (and can hold the file's URI).
            PdfRead.Error(UiText.Res(R.string.import_statement_error_read))
        }
    }

    /**
     * A PDF that opened but isn't one PennyWise reads: its bank is unknown, but its
     * password is kept so the same bank's other statements open (and show up as
     * unsupported rather than forever locked).
     */
    private fun rememberUnsupported(password: String?) {
        if (password != null) runCatching { passwordStore.save("unsupported", password.hashCode().toUInt().toString(16), password) }
    }

    /** Records what a card statement says it earned (or, if no total is found, the lines that mention rewards). */
    private fun recordRewards(
        text: String, summary: CardStatementSummary?, rowRewards: java.math.BigDecimal? = null,
        rows: List<StatementRowReward> = emptyList()
    ) {
        runCatching { StatementRewardText.read(text, summary, rowRewards, rows)?.let(statementRewardStore::record) }
    }

    /** Statements already tried for rewards since the app started, so one that yields nothing is not re-opened by every screen. */
    private val rewardsTried: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    private val rewardsMutex = Mutex()

    /**
     * Reads the reward totals off every statement saved on the phone that has not been read for rewards yet,
     * so earlier statements (not just new imports) can check the app's reward estimates. One reader at a time:
     * an opened statement is a single shared temporary file, and the Home card and the Card rewards screen both ask.
     * [retry] also tries statements that failed earlier (a Gmail fetch may have taught the app their password).
     */
    suspend fun refreshStatementRewards(retry: Boolean = false) = withContext(Dispatchers.IO) {
        rewardsMutex.withLock {
            for (saved in savedStatementStore.list().filter { it.cardLast4 != null && !it.unsupported && it.statementDay > 0 }) {
                val key = StatementRewardEntry(saved.bankName, saved.cardLast4!!, saved.statementDay).key
                if (statementRewardStore.has(key) || (!retry && saved.id in rewardsTried)) continue
                try {
                    val file = savedStatementStore.openPlain(saved.id)
                    try {
                        val (text, _) = unlock(Uri.fromFile(file), null)
                        val parser = PdfParserFactory.getParser(text) as? CardStatementParser
                        if (parser != null) recordRewards(text, parser.summary(text), parser.rowRewards(text), parser.rowRewardDetails(text))
                    } finally {
                        savedStatementStore.closePlain()
                    }
                } catch (e: CancellationException) {
                    throw e // not tried: the next run picks it up
                } catch (_: Exception) {
                    // Locked or unreadable: leave it for the person, and do not retry until the app restarts.
                }
                rewardsTried += saved.id
            }
        }
    }

    /**
     * Reads the due date off statements already saved on the phone (fetched before
     * the Home "payments due" card existed, or read without being imported), so the
     * card appears without waiting for the next statement. The newest per card only.
     */
    suspend fun refreshCardDues() = withContext(Dispatchers.IO) {
        val newest = savedStatementStore.list()
            .filter { it.cardLast4 != null && !it.unsupported }
            .groupBy { it.bankName.trim().lowercase() to it.cardLast4 }
            .values.mapNotNull { group -> group.maxByOrNull { it.sortMillis } }
        for (saved in newest) {
            val known = cardDueStore.dues.value.firstOrNull {
                it.key == CardDue.keyFor(saved.bankName, saved.cardLast4!!)
            }
            if (known != null && known.statementDate.toEpochDay() >= saved.statementDay && saved.statementDay > 0) continue
            runCatching {
                val file = savedStatementStore.openPlain(saved.id)
                try {
                    val uri = Uri.fromFile(file)
                    val (text, _) = unlock(uri, null)
                    (PdfParserFactory.getParser(text) as? CardStatementParser)?.summary(text)?.let(cardDueStore::record)
                } finally {
                    savedStatementStore.closePlain()
                }
            }
        }
    }
    /**
     * The PDF's text and the password that opened it (null: it isn't locked).
     * A locked PDF with no password given is tried against the remembered ones,
     * since its bank isn't known until it opens; a typed password that fails
     * is the user's to correct.
     */
    private fun unlock(uri: Uri, password: String?): Pair<String, String?> {
        try {
            return PdfTextExtractor.extractText(context, uri, password) to password
        } catch (locked: PdfPasswordRequiredException) {
            if (password != null) throw locked
            for (candidate in passwordStore.all()) {
                try {
                    return PdfTextExtractor.extractText(context, uri, candidate) to candidate
                } catch (_: PdfPasswordRequiredException) {
                    // Another bank's password; keep trying.
                }
            }
            throw locked
        }
    }

    /**
     * Keeps what a successful read needs next time: the PDF itself, and the
     * password that opened it, under the statement's bank and card. Never lets a
     * storage problem fail the import.
     */
    private fun remember(uri: Uri, password: String?, parsed: List<ParsedTransaction>, summary: CardStatementSummary?): String? {
        val identity = describe(parsed, summary)
        if (password != null) runCatching { passwordStore.save(identity.bankName, identity.cardLast4, password) }
        return runCatching {
            val pdf = context.contentResolver.openInputStream(uri)?.use { it.readNBytesCompat(MAX_FILE_BYTES + 1) }
                ?.takeIf { it.size <= MAX_FILE_BYTES } ?: return null
            savedStatementStore.save(
                pdf, identity.bankName, identity.cardLast4, identity.label,
                accountKey = identity.accountKey, statementDay = identity.statementDay
            ).id
        }.getOrNull()
    }

    /** Bank, card, statement date and a label for a statement that has been read. */
    private fun describe(parsed: List<ParsedTransaction>, summary: CardStatementSummary?): StatementIdentity {
        val bank = parsed.first().bankName
        val card = summary?.cardLast4 ?: parsed.mapNotNull { it.accountLast4 }.distinct().singleOrNull()
        val days = parsed.map { StatementBalanceCheck.dateOf(it) }
        val date = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
        val account = if (card != null) "$bank ••$card" else bank
        val period = if (summary != null) date.format(summary.statementDate) else "${date.format(days.min())} – ${date.format(days.max())}"
        return StatementIdentity(
            bankName = bank,
            cardLast4 = card,
            accountKey = StatementPasswordStore.entryKey(bank, card),
            statementDay = (summary?.statementDate ?: days.max()).toEpochDay(),
            label = "$account · $period"
        )
    }

    /**
     * Who a PDF is for and when, using only the remembered passwords — no prompt.
     * [Identification.Locked] when none opens it (yet), [Identification.Unsupported]
     * when it opens but PennyWise can't read that kind of statement.
     */
    suspend fun identify(uri: Uri): Identification = withContext(Dispatchers.IO) {
        try {
            val (text, _) = unlock(uri, null)
            val parser = PdfParserFactory.getParser(text) ?: return@withContext Identification.Unsupported
            val parsed = parser.parse(text).takeIf { it.isNotEmpty() } ?: return@withContext Identification.Unsupported
            Identification.Known(describe(parsed, (parser as? CardStatementParser)?.summary(text)))
        } catch (e: PdfPasswordRequiredException) {
            Identification.Locked
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Identification.Unsupported
        }
    }

    fun markStatementImported(savedId: String) = savedStatementStore.markImported(savedId)

    fun savedStatements(): List<SavedStatement> = savedStatementStore.list()

    /** The saved PDF as a file the regular PDF import can open. */
    fun savedStatementUri(id: String): Uri = Uri.fromFile(savedStatementStore.openPlain(id))

    fun deleteSavedStatement(id: String) = savedStatementStore.delete(id)

    /** Removes the readable copy a saved statement was opened from. */
    fun closeOpenedStatement() = savedStatementStore.closePlain()

    fun hasSavedPasswords(): Boolean = !passwordStore.isEmpty()

    fun forgetSavedPasswords() = passwordStore.clear()

    /** A spreadsheet the user can fix; [text] says what's wrong, in the app's language. */
    class UnreadableFileException(val text: UiText) : Exception()

    /** The spreadsheet's rows; throws [UnreadableFileException] for a file the user can fix. */
    suspend fun readTable(uri: Uri): List<List<String>> = withContext(Dispatchers.IO) {
        fun unreadable(id: Int, vararg args: Any) = UnreadableFileException(UiText.Res(id, args.toList()))
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            input.readNBytesCompat(MAX_FILE_BYTES + 1).takeIf { it.size <= MAX_FILE_BYTES }
                ?: throw unreadable(R.string.import_statement_error_too_large)
        } ?: throw unreadable(R.string.import_statement_error_read)
        val rows = try {
            StatementTableParser.readTable(bytes)
        } catch (e: OutOfMemoryError) {
            // A hostile or enormous file: tell the user rather than crash the app.
            throw unreadable(R.string.import_statement_error_too_large)
        } catch (e: StatementTableParser.LegacyXlsException) {
            throw unreadable(R.string.import_statement_xls_hint)
        } catch (e: StatementTableParser.TooManyRowsException) {
            throw unreadable(R.string.import_statement_error_too_many_rows, XlsxReader.MAX_ROWS)
        } catch (e: XlsxReader.Date1904Exception) {
            throw unreadable(R.string.import_statement_error_date_1904)
        }
        if (rows.none { row -> row.any(String::isNotBlank) }) throw unreadable(R.string.import_statement_error_no_rows)
        rows
    }

    suspend fun savedMappings(): Map<String, StatementColumnMapping> =
        StatementTableParser.decodeMappings(preferences.getStatementColumnMappings())

    suspend fun saveMapping(rows: List<List<String>>, mapping: StatementColumnMapping) {
        val signature = StatementTableParser.signature(rows.getOrNull(mapping.headerRow).orEmpty())
        if (signature.isEmpty()) return
        val updated = savedMappings() + (signature to mapping)
        preferences.setStatementColumnMappings(StatementTableParser.encodeMappings(updated))
    }

    /** Accounts a statement can belong to (credit cards included; their balance isn't checked). */
    suspend fun accounts(): List<AccountBalanceEntity> =
        accountBalanceRepository.getAllLatestBalancesOnce().sortedBy { it.displayLabel.lowercase() }

    /** What an account's amounts are tagged with — never the stored INR default for an SMS-tracked foreign account. */
    fun currencyOf(account: AccountBalanceEntity): String =
        CurrencyFormatter.resolveAccountCurrency(account.sourceType, account.currency, account.bankName)

    suspend fun baseCurrency(): String = preferences.baseCurrency.first()

    /** Matches [parsed] against PennyWise exactly as [commit] will, writing nothing. */
    suspend fun preview(parsed: List<ParsedTransaction>): StatementImportPlan = withContext(Dispatchers.IO) {
        StatementImportProcessor(repositoryStore()).plan(parsed)
    }

    /**
     * Imports [parsed], adding only the rows at [keep] (the preview's new rows
     * the user left ticked). Matching runs again inside one database
     * transaction — the protection SMS writes use — so an SMS saved or edited
     * while the preview was open is matched as it is now, never overwritten
     * with the preview's copy or duplicated. A manual account's balance
     * follows its new rows.
     */
    suspend fun commit(parsed: List<ParsedTransaction>, keep: Set<Int>): StatementImportResult.Success =
        withContext(Dispatchers.IO) {
            val result = commitRows(parsed, keep)
            // Rows from a statement can be the other side of a payment to one of the person's own accounts.
            runCatching { ownPayeeMirror.applyAll() }
            result
        }

    private suspend fun commitRows(parsed: List<ParsedTransaction>, keep: Set<Int>): StatementImportResult.Success =
        run {
            database.withTransaction {
                val processor = StatementImportProcessor(repositoryStore())
                val plan = processor.plan(parsed)
                val excluded = plan.rows.indices.toSet() - keep
                val accounts = plan.newIndices.filter { it !in excluded }
                    .map { plan.rows[it].parsed }
                    .mapNotNull { row -> row.accountLast4?.let { row.bankName to it } }
                    .distinct()
                // Pin a legacy manual account's opening before the rows land, so the
                // recompute below adds them instead of absorbing them into the opening.
                accounts.forEach { (bank, last4) -> accountBalanceRepository.ensureManualOpening(bank, last4) }
                val result = processor.apply(plan, excluded)
                accounts.forEach { (bank, last4) -> accountBalanceRepository.recomputeManualBalance(bank, last4) }
                result
            }
        }

    /**
     * The account a PDF statement is about: the one account with the rows'
     * bank name (and last 4, when the statement prints it). Null when it's
     * ambiguous or unknown.
     */
    suspend fun resolveAccount(parsed: List<ParsedTransaction>): AccountBalanceEntity? {
        val bank = parsed.map { it.bankName }.distinct().singleOrNull() ?: return null
        val last4 = parsed.mapNotNull { it.accountLast4 }.distinct().let { if (it.size > 1) return null else it.singleOrNull() }
        return accounts()
            // A statement that prints no account number is a bank account's, never a card's.
            .filter { it.bankName.equals(bank, ignoreCase = true) && if (last4 == null) !it.isCreditCard else it.accountLast4 == last4 }
            .singleOrNull()
    }

    /**
     * Statement's closing balance vs PennyWise's for [account] at the end of the
     * statement's last day. Null when there's nothing to compare: no balance in
     * the statement, no account, a credit card (its statement balance is what's
     * owed, not held), or a statement in another currency.
     */
    suspend fun balanceCheck(
        parsed: List<ParsedTransaction>,
        account: AccountBalanceEntity?,
        cardSummary: CardStatementSummary? = null
    ): StatementBalanceCheck? {
        account ?: return null
        if (account.isCreditCard) return cardSummary?.let { cardCheck(it, account) }
        val currency = currencyOf(account)
        val closing = StatementBalanceCheck.closingRow(parsed.filter { it.currency == currency }) ?: return null
        val asOf = StatementBalanceCheck.dateOf(closing)
        val end = asOf.atTime(LocalTime.MAX)
        return withContext(Dispatchers.IO) {
            val bank = account.bankName
            val last4 = account.accountLast4
            val statementBalance = closing.balance!!
            val appBalance = accountBalanceRepository.balanceAsOf(bank, last4, end)
            val latest = accountBalanceRepository.getLatestBalance(bank, last4)
            StatementBalanceCheck(
                bankName = bank,
                accountLast4 = last4,
                accountLabel = account.displayLabel,
                currency = currency,
                asOf = asOf,
                statementBalance = statementBalance,
                appBalance = appBalance,
                correction = StatementBalanceCheck.correctionFor(
                    statementBalance = statementBalance,
                    appBalance = appBalance,
                    currentBalance = latest?.balance,
                    isManual = accountBalanceRepository.isManualAccount(bank, last4),
                    recordedAfterStatement = latest != null && latest.timestamp > end
                )
            )
        }
    }

    /**
     * "Set balance": recomputes the check and writes its correction in one
     * transaction, so a balance SMS landing after the result screen opened is
     * read rather than overwritten. Returns the fresh check (null: nothing written).
     */
    suspend fun applyBalance(
        parsed: List<ParsedTransaction>,
        account: AccountBalanceEntity,
        cardSummary: CardStatementSummary? = null
    ): StatementBalanceCheck? =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                if (account.isCreditCard) {
                    val summary = cardSummary ?: return@withTransaction null
                    return@withTransaction applyCardSummary(summary, account)
                }
                val check = balanceCheck(parsed, account) ?: return@withTransaction null
                val target = check.correction ?: return@withTransaction null
                // Dated at the statement's end (never in the future) so a later
                // bank balance still wins; a manual account derives its own.
                val at = minOf(check.asOf.atTime(LocalTime.MAX), LocalDateTime.now())
                accountBalanceRepository.setCurrentBalance(check.bankName, check.accountLast4, target, at)
                check
            }
        }

    /**
     * A card statement against PennyWise's card: what the statement says was owed,
     * what the app had, and the correction that makes the card agree. Null when
     * a newer statement has already been applied — applying
     * an old statement over a newer one would roll the card back.
     */
    private suspend fun cardCheck(summary: CardStatementSummary, account: AccountBalanceEntity): StatementBalanceCheck? =
        withContext(Dispatchers.IO) {
            val bank = account.bankName
            val last4 = account.accountLast4
            val key = StatementPasswordStore.entryKey(summary.bankName, summary.cardLast4)
            val day = summary.statementDate.toEpochDay()
            if (savedStatementStore.list().any { it.accountKey == key && it.appliedAt > 0 && it.statementDay > day }) {
                return@withContext null
            }
            val latest = accountBalanceRepository.getLatestBalance(bank, last4) ?: return@withContext null
            val end = summary.statementDate.atTime(LocalTime.MAX)
            // Shown as "PennyWise says": the history's figure on the statement date.
            val appBalance = accountBalanceRepository.balanceAsOf(bank, last4, end)
            val target = if (StatementBalanceCheck.isPlausible(summary.owed, summary.creditLimit)) {
                StatementBalanceCheck.cardTarget(
                    statementOutstanding = summary.owed,
                    statementLimit = summary.creditLimit,
                    currentBalance = latest.balance,
                    currentLimit = latest.creditLimit,
                    reportsAvailable = accountBalanceRepository.reportsAvailableLimit(bank, last4),
                    changeAfterStatement = accountBalanceRepository.cardChangeAfter(bank, last4, end)
                )
            } else {
                null
            }
            StatementBalanceCheck(
                bankName = bank,
                accountLast4 = last4,
                accountLabel = account.displayLabel,
                currency = currencyOf(account),
                asOf = summary.statementDate,
                statementBalance = summary.owed,
                appBalance = appBalance,
                correction = target
            )
        }

    /**
     * Applies a card statement: the card's outstanding corrected to agree with it,
     * plus the statement's credit limit and statement day — the figures that used
     * to be hard-coded per card. Runs inside [applyBalance]'s transaction.
     * Null when nothing needed changing or the statement is not to be applied
     * (already applied, older than one that was, or figures that don't look right).
     */
    private suspend fun applyCardSummary(summary: CardStatementSummary, account: AccountBalanceEntity): StatementBalanceCheck? {
        val bank = account.bankName
        val last4 = account.accountLast4
        if (!StatementBalanceCheck.isPlausible(summary.owed, summary.creditLimit)) return null
        val check = cardCheck(summary, account) ?: return null
        val latest = accountBalanceRepository.getLatestBalance(bank, last4) ?: return null
        val limit = summary.creditLimit ?: latest.creditLimit
        val day = summary.statementDate.dayOfMonth
        val limitChanged = limit != null && latest.creditLimit?.compareTo(limit) != 0
        val key = StatementPasswordStore.entryKey(summary.bankName, summary.cardLast4)
        val statementDay = summary.statementDate.toEpochDay()
        if (check.correction == null && !limitChanged && latest.statementDay == day) {
            savedStatementStore.markApplied(key, statementDay)
            return null
        }
        accountBalanceRepository.insertBalance(
            latest.copy(
                id = 0,
                balance = check.correction ?: latest.balance,
                creditLimit = limit,
                statementDay = day,
                timestamp = LocalDateTime.now(),
                transactionId = null,
                // MANUAL: a rescan keeps it, like any user-set balance.
                sourceType = "MANUAL",
                smsSource = null
            )
        )
        accountBalanceRepository.updateStatementDay(bank, last4, day)
        savedStatementStore.markApplied(key, statementDay)
        return check
    }

    /**
     * A card statement for a card PennyWise doesn't track yet (a new card, or one
     * that has had no SMS): creates the card from the statement — its limit,
     * statement day and what it owed on the statement date — so it appears as
     * soon as its first statement is imported. Returns the card's account, or
     * null when the summary names no card.
     */
    suspend fun ensureCardAccount(summary: CardStatementSummary): AccountBalanceEntity? = withContext(Dispatchers.IO) {
        val last4 = summary.cardLast4 ?: return@withContext null
        accountBalanceRepository.getLatestBalance(summary.bankName, last4)?.let { return@withContext it }
        database.withTransaction {
            cardRepository.findOrCreateCard(last4, summary.bankName, isCredit = true)
            accountBalanceRepository.insertBalance(
                AccountBalanceEntity(
                    bankName = summary.bankName,
                    accountLast4 = last4,
                    balance = summary.outstanding,
                    // At the statement's end: the statement's own rows are already in it.
                    timestamp = minOf(summary.statementDate.atTime(LocalTime.MAX), LocalDateTime.now()),
                    creditLimit = summary.creditLimit,
                    isCreditCard = true,
                    accountType = "CREDIT",
                    currency = "INR",
                    statementDay = summary.statementDate.dayOfMonth,
                    sourceType = "MANUAL"
                )
            )
        }
        accountBalanceRepository.getLatestBalance(summary.bankName, last4)
    }

    private fun repositoryStore() = object : StatementImportProcessor.TransactionStore {
        override suspend fun getTransactionsByHashes(hashes: List<String>): List<TransactionEntity> =
            transactionRepository.getTransactionsByHashes(hashes)

        override suspend fun getTransactionsBetween(start: LocalDateTime, end: LocalDateTime): List<TransactionEntity> =
            transactionRepository.getTransactionsBetweenDatesList(start, end)

        override suspend fun updateTransaction(transaction: TransactionEntity) {
            transactionRepository.updateTransaction(transaction)
        }

        override suspend fun insertTransactions(transactions: List<TransactionEntity>) {
            transactionRepository.insertTransactions(transactions)
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 20 * 1024 * 1024

        private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() < limit) {
                val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }
}

/** Who a statement PDF is for and when, once it has been read. */
data class StatementIdentity(
    val bankName: String,
    val cardLast4: String?,
    /** [StatementPasswordStore.entryKey] of the bank and card. */
    val accountKey: String,
    /** The statement date (epoch day). */
    val statementDay: Long,
    val label: String
)

/** The outcome of opening a statement PDF with only the remembered passwords. */
sealed interface Identification {
    data class Known(val who: StatementIdentity) : Identification
    /** No remembered password opens it. */
    data object Locked : Identification
    /** It opens, but PennyWise has no reader for it (or it has nothing to import). */
    data object Unsupported : Identification
}
