package com.moneytracker.app.presentation.statement

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.moneytracker.app.BuildConfig
import com.moneytracker.app.R
import com.moneytracker.app.ui.UiText
import androidx.lifecycle.viewModelScope
import com.moneytracker.parser.core.ParsedTransaction
import com.moneytracker.app.billing.EntitlementGate
import com.moneytracker.app.data.database.entity.AccountBalanceEntity
import com.moneytracker.app.data.preferences.UserPreferencesRepository
import com.moneytracker.app.data.statement.ImportStatementUseCase
import com.moneytracker.app.data.statement.StatementAccount
import com.moneytracker.app.data.statement.StatementBalanceCheck
import com.moneytracker.app.data.statement.StatementColumnMapping
import com.moneytracker.app.data.statement.StatementImportPlan
import com.moneytracker.app.data.statement.StatementImportResult
import com.moneytracker.app.data.statement.StatementTableParser
import com.moneytracker.app.widget.WidgetRefresher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

sealed class ImportStatementUiState {
    data object Idle : ImportStatementUiState()
    data object Loading : ImportStatementUiState()
    /** Waiting for the locked PDF's password; [wrongPassword] after a failed try. */
    data class PasswordRequired(val uri: Uri, val wrongPassword: Boolean) : ImportStatementUiState()

    /** A spreadsheet is open and its columns are being mapped. */
    data class Mapping(
        val rows: List<List<String>>,
        val mapping: StatementColumnMapping,
        val accounts: List<AccountBalanceEntity>,
        /** The first rows as the mapping reads them, so a wrong column or date format shows. */
        val sample: List<ParsedTransaction>,
        val parsedCount: Int,
        val skippedRows: Int,
        val detectedDateFormat: String?
    ) : ImportStatementUiState()

    /** Matched against PennyWise; nothing written until [ImportStatementViewModel.confirmImport]. */
    data class Preview(
        val plan: StatementImportPlan,
        val excluded: Set<Int> = emptySet(),
        val accountLabel: String?,
        /** Spreadsheet imports can go back to the mapping; PDFs have none. */
        val fromSpreadsheet: Boolean
    ) : ImportStatementUiState()

    data class Success(
        val result: StatementImportResult.Success,
        val balanceCheck: StatementBalanceCheck? = null,
        val balanceApplied: Boolean = false
    ) : ImportStatementUiState()

    data class Error(val message: UiText) : ImportStatementUiState()
}

/** Where a multi-file pick has got to: statement [position] of [total]. */
data class StatementBatch(val position: Int, val total: Int) {
    val hasNext: Boolean get() = position < total
}

@HiltViewModel
class ImportStatementViewModel @Inject constructor(
    private val importStatementUseCase: ImportStatementUseCase,
    private val preferences: UserPreferencesRepository,
    entitlementGate: EntitlementGate,
    @ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ImportStatementUiState>(ImportStatementUiState.Idle)
    val uiState: StateFlow<ImportStatementUiState> = _uiState.asStateFlow()

    private val _savedStatements = MutableStateFlow<List<com.moneytracker.app.data.statement.SavedStatement>>(emptyList())
    /** Statement PDFs kept from earlier imports, newest first. */
    val savedStatements: StateFlow<List<com.moneytracker.app.data.statement.SavedStatement>> = _savedStatements.asStateFlow()

    private val _hasSavedPasswords = MutableStateFlow(false)
    val hasSavedPasswords: StateFlow<Boolean> = _hasSavedPasswords.asStateFlow()

    init {
        refreshSaved()
    }

    private fun refreshSaved() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                _savedStatements.value = importStatementUseCase.savedStatements()
                _hasSavedPasswords.value = importStatementUseCase.hasSavedPasswords()
            }
        }
    }

    /** Imports a kept PDF again; its password is already remembered. */
    fun openSavedStatement(statement: com.moneytracker.app.data.statement.SavedStatement) {
        importStatement(importStatementUseCase.savedStatementUri(statement.id))
    }

    fun deleteSavedStatement(statement: com.moneytracker.app.data.statement.SavedStatement) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { importStatementUseCase.deleteSavedStatement(statement.id) }
            refreshSaved()
        }
    }

    fun forgetSavedPasswords() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { importStatementUseCase.forgetSavedPasswords() }
            refreshSaved()
        }
    }

    /** The statement being previewed: its rows and the account its balance is checked on. */
    private var pending: Pending? = null

    /** The mapping screen's in-flight re-parse; a newer edit cancels it. */
    private var mappingJob: Job? = null

    private data class Pending(
        val parsed: List<ParsedTransaction>,
        val account: AccountBalanceEntity?,
        val table: List<List<String>>? = null,
        val mapping: StatementColumnMapping? = null,
        /** A card statement's summary: what the card owed and its limits. */
        val cardSummary: com.moneytracker.app.data.statement.CardStatementSummary? = null,
        /** The kept copy of the PDF this came from, marked imported once it is. */
        val savedId: String? = null
    )

    /**
     * True when the user is allowed to import a statement right now — either
     * Pro (unlimited) or has not yet imported this calendar month. The UI
     * reads this to decide between opening the file picker and triggering
     * the paywall. PDF and spreadsheet statements share the one quota.
     *
     * Calendar-month boundary is computed in the system default zone, which
     * matches how a user thinks about "this month" without needing a
     * stored month-string that's fragile across timezone changes.
     */
    val canImportThisMonth: StateFlow<Boolean> = combine(
        entitlementGate.isProEntitled,
        preferences.lastStatementImportAt,
    ) { pro, lastAtMillis ->
        if (pro) return@combine true
        if (lastAtMillis == null) return@combine true
        val lastDate = Instant.ofEpochMilli(lastAtMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now(ZoneId.systemDefault())
        lastDate.year != today.year || lastDate.month != today.month
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true,
    )

    /** PDFs picked together, not yet started; the one on screen isn't in here. */
    private val batchQueue = ArrayDeque<Uri>()

    private val _batch = MutableStateFlow<StatementBatch?>(null)
    /** Set while working through several picked PDFs; null for a single one. */
    val batch: StateFlow<StatementBatch?> = _batch.asStateFlow()

    /**
     * Several PDFs picked at once go through the usual flow one after another:
     * each gets its own password prompt (when no remembered one opens it),
     * preview and result. [nextStatement] moves on.
     */
    fun importStatements(uris: List<Uri>) {
        val picked = uris.distinct()
        if (picked.isEmpty()) return
        batchQueue.clear()
        batchQueue.addAll(picked.drop(1))
        _batch.value = if (picked.size > 1) StatementBatch(1, picked.size) else null
        importStatement(picked.first())
    }

    /**
     * Done with the statement on screen — imported, skipped or unreadable: the
     * next picked one, or back to the start when none is left.
     */
    fun nextStatement() {
        val next = batchQueue.removeFirstOrNull()
        val batch = _batch.value
        if (next == null || batch == null) {
            resetState()
            return
        }
        mappingJob?.cancel()
        pending = null
        _batch.value = batch.copy(position = batch.position + 1)
        _uiState.value = ImportStatementUiState.Loading
        viewModelScope.launch {
            withContext(Dispatchers.IO) { importStatementUseCase.closeOpenedStatement() }
            importStatement(next)
        }
    }

    fun importStatement(uri: Uri, password: String? = null) {
        _uiState.value = ImportStatementUiState.Loading
        viewModelScope.launch {
            when (val read = importStatementUseCase.readPdf(uri, password)) {
                is ImportStatementUseCase.PdfRead.Parsed -> {
                    val account = importStatementUseCase.resolveAccount(read.transactions)
                    showPreview(Pending(read.transactions, account, cardSummary = read.cardSummary, savedId = read.savedId))
                }
                is ImportStatementUseCase.PdfRead.Error ->
                    _uiState.value = ImportStatementUiState.Error(read.message)
                is ImportStatementUseCase.PdfRead.PasswordRequired ->
                    _uiState.value = ImportStatementUiState.PasswordRequired(uri, read.wrongPassword)
            }
        }
    }

    /** An .xlsx/.csv statement: straight to the preview when its header has a saved mapping. */
    fun importSpreadsheet(uri: Uri) {
        _uiState.value = ImportStatementUiState.Loading
        viewModelScope.launch {
            try {
                val rows = importStatementUseCase.readTable(uri)
                val accounts = importStatementUseCase.accounts()
                val saved = StatementTableParser.findSavedMapping(rows, importStatementUseCase.savedMappings())
                if (saved != null && savedAccountIsSure(rows, saved, accounts) && previewSpreadsheet(rows, saved, accounts)) {
                    return@launch
                }
                showMapping(rows, withNamedAccount(rows, saved ?: StatementTableParser.guessMapping(rows), accounts), accounts)
            } catch (e: ImportStatementUseCase.UnreadableFileException) {
                _uiState.value = ImportStatementUiState.Error(e.text)
            } catch (e: Exception) {
                _uiState.value = ImportStatementUiState.Error(UiText.Res(R.string.import_statement_error_read))
            }
        }
    }

    /**
     * A saved mapping skips straight to the preview only when the file itself
     * says it's that account's statement: its last 4 above the header
     * ("Account: XXXXXX4321"), or its bank's name when that's the only account
     * at the bank. Headers alone prove nothing — two banks (or two accounts at
     * one bank) can export identical columns. Otherwise the user confirms.
     */
    private fun savedAccountIsSure(
        rows: List<List<String>>,
        saved: StatementColumnMapping,
        accounts: List<AccountBalanceEntity>
    ): Boolean {
        val bank = saved.accountBankName ?: return true // unlinked: nothing to book wrongly
        val last4 = saved.accountLast4 ?: return true
        if (accounts.none { it.bankName == bank && it.accountLast4 == last4 }) return false
        val preamble = StatementTableParser.preamble(rows, saved.headerRow)
        // Sure only when the saved account is the one account the file names.
        val named = accounts.filter { StatementTableParser.namesAccount(preamble, it.accountLast4) }
        if (named.isNotEmpty()) return named.singleOrNull()?.let { it.bankName == bank && it.accountLast4 == last4 } == true
        return preamble.contains(bank, ignoreCase = true) && accounts.count { it.bankName == bank } == 1
    }

    /** [mapping] with the account the file names above its header, when exactly one matches. */
    private fun withNamedAccount(
        rows: List<List<String>>,
        mapping: StatementColumnMapping,
        accounts: List<AccountBalanceEntity>
    ): StatementColumnMapping {
        val preamble = StatementTableParser.preamble(rows, mapping.headerRow)
        val named = accounts.filter { StatementTableParser.namesAccount(preamble, it.accountLast4) }.singleOrNull()
            ?: return mapping
        return mapping.copy(accountBankName = named.bankName, accountLast4 = named.accountLast4)
    }

    fun updateMapping(mapping: StatementColumnMapping) {
        val state = _uiState.value as? ImportStatementUiState.Mapping ?: return
        // The choice shows at once (so a second quick change builds on it); the
        // sample catches up, and only the newest parse may land.
        _uiState.value = state.copy(mapping = mapping)
        mappingJob?.cancel()
        mappingJob = viewModelScope.launch { showMapping(state.rows, mapping, state.accounts) }
    }

    /** Use the header row at [row] and re-guess the columns from its names. */
    fun selectHeaderRow(row: Int) {
        val state = _uiState.value as? ImportStatementUiState.Mapping ?: return
        val guessed = StatementTableParser.guessMapping(state.rows, row.coerceIn(0, state.rows.lastIndex))
        updateMapping(guessed.copy(accountBankName = state.mapping.accountBankName, accountLast4 = state.mapping.accountLast4))
    }

    fun confirmMapping() {
        val state = _uiState.value as? ImportStatementUiState.Mapping ?: return
        mappingJob?.cancel()
        _uiState.value = ImportStatementUiState.Loading
        viewModelScope.launch {
            importStatementUseCase.saveMapping(state.rows, state.mapping)
            if (!previewSpreadsheet(state.rows, state.mapping, state.accounts)) {
                showMapping(state.rows, state.mapping, state.accounts)
            }
        }
    }

    fun editMapping() {
        val p = pending ?: return
        val rows = p.table ?: return
        val mapping = p.mapping ?: return
        viewModelScope.launch { showMapping(rows, mapping, importStatementUseCase.accounts()) }
    }

    fun toggleRow(index: Int) {
        val state = _uiState.value as? ImportStatementUiState.Preview ?: return
        _uiState.value = state.copy(
            excluded = if (index in state.excluded) state.excluded - index else state.excluded + index
        )
    }

    fun confirmImport() {
        val state = _uiState.value as? ImportStatementUiState.Preview ?: return
        var p = pending ?: return
        _uiState.value = ImportStatementUiState.Loading
        viewModelScope.launch {
            try {
                // A statement for a card PennyWise doesn't track yet creates the card.
                val summary = p.cardSummary
                if (p.account == null && summary != null) {
                    p = p.copy(account = importStatementUseCase.ensureCardAccount(summary))
                    pending = p
                }
                val keep = state.plan.newIndices.toSet() - state.excluded
                val result = importStatementUseCase.commit(p.parsed, keep)
                p.savedId?.let { withContext(Dispatchers.IO) { importStatementUseCase.markStatementImported(it) } }
                // Record the import so the next attempt this month is
                // gated; Pro users will still see canImportThisMonth=true
                // because the combine() short-circuits on entitlement. A
                // statement that was already fully in PennyWise changed
                // nothing, so it doesn't use up the month's free import.
                if (result.imported + result.enriched > 0) {
                    preferences.markStatementImported(System.currentTimeMillis())
                    WidgetRefresher.refreshTransactionWidgets(appContext)
                }
                val card = p.account
                if (card != null && p.cardSummary != null) {
                    // A card statement is the bank's own figure for the limit and what was owed:
                    // applied straight away (the rules in applyBalance keep it safe to repeat),
                    // and the result screen says what changed.
                    val applied = importStatementUseCase.applyBalance(p.parsed, card, p.cardSummary)
                    if (applied != null) WidgetRefresher.refreshTransactionWidgets(appContext)
                    _uiState.value = ImportStatementUiState.Success(result, applied, balanceApplied = applied != null)
                } else {
                    val check = importStatementUseCase.balanceCheck(p.parsed, p.account, p.cardSummary)
                    _uiState.value = ImportStatementUiState.Success(result, check)
                }
            } catch (e: Exception) {
                _uiState.value = ImportStatementUiState.Error(e.message?.let { UiText.Plain(it) } ?: UiText.Res(R.string.import_statement_error_failed))
            }
        }
    }

    /** Sets the account's balance so it agrees with the statement. */
    fun applyStatementBalance() {
        val state = _uiState.value as? ImportStatementUiState.Success ?: return
        val p = pending ?: return
        val account = p.account ?: return
        viewModelScope.launch {
            // Recomputed at tap time: a balance SMS since the screen opened counts.
            val applied = importStatementUseCase.applyBalance(p.parsed, account, p.cardSummary)
            _uiState.value = if (applied != null) {
                WidgetRefresher.refreshTransactionWidgets(appContext)
                state.copy(balanceCheck = applied, balanceApplied = true)
            } else {
                state.copy(balanceCheck = importStatementUseCase.balanceCheck(p.parsed, account, p.cardSummary))
            }
        }
    }

    private suspend fun showMapping(rows: List<List<String>>, mapping: StatementColumnMapping, accounts: List<AccountBalanceEntity>) {
        val target = statementAccount(mapping, accounts).second
        val (parsed, dateFormat) = withContext(Dispatchers.Default) {
            // Any column can be picked for any role while mapping; a choice that reads
            // nothing sensible shows "no transactions", it never takes the app down.
            val parsed = if (mapping.isComplete) {
                runCatching { StatementTableParser.parse(rows, mapping, target) }.getOrNull()
            } else {
                null
            }
            val format = if (mapping.dateColumn >= 0) {
                runCatching { StatementTableParser.effectiveDateFormat(rows, mapping) }.getOrNull()
            } else {
                null
            }
            parsed to format
        }
        // A superseded parse (newer edit, Continue, Cancel) must not land.
        currentCoroutineContext().ensureActive()
        _uiState.value = ImportStatementUiState.Mapping(
            rows = rows,
            mapping = mapping,
            accounts = accounts,
            sample = parsed?.transactions?.take(SAMPLE_ROWS).orEmpty(),
            parsedCount = parsed?.transactions?.size ?: 0,
            skippedRows = parsed?.skippedRows ?: 0,
            detectedDateFormat = dateFormat
        )
    }

    /** False when the mapping reads no transactions (the caller shows the mapping instead). */
    private suspend fun previewSpreadsheet(
        rows: List<List<String>>,
        mapping: StatementColumnMapping,
        accounts: List<AccountBalanceEntity>
    ): Boolean {
        if (!mapping.isComplete) return false
        val (account, target) = statementAccount(mapping, accounts)
        val parsed = withContext(Dispatchers.Default) {
            runCatching { StatementTableParser.parse(rows, mapping, target).transactions }.getOrDefault(emptyList())
        }
        if (parsed.isEmpty()) return false
        showPreview(Pending(parsed, account, rows, mapping))
        return true
    }

    private suspend fun showPreview(p: Pending) {
        pending = p
        _uiState.value = ImportStatementUiState.Preview(
            plan = importStatementUseCase.preview(p.parsed),
            accountLabel = p.account?.displayLabel,
            fromSpreadsheet = p.table != null
        )
    }

    /** The linked account and what its rows are booked as; unlinked rows use the base currency. */
    private suspend fun statementAccount(
        mapping: StatementColumnMapping,
        accounts: List<AccountBalanceEntity>
    ): Pair<AccountBalanceEntity?, StatementAccount> {
        val account = accounts.firstOrNull { it.bankName == mapping.accountBankName && it.accountLast4 == mapping.accountLast4 }
        return if (account != null) {
            account to StatementAccount(account.bankName, account.accountLast4, importStatementUseCase.currencyOf(account))
        } else {
            null to StatementAccount(UNLINKED_BANK, null, importStatementUseCase.baseCurrency())
        }
    }

    /** Back to the start; also drops any picked statements not yet reached. */
    fun resetState() {
        mappingJob?.cancel()
        pending = null
        batchQueue.clear()
        _batch.value = null
        _uiState.value = ImportStatementUiState.Idle
        viewModelScope.launch(Dispatchers.IO) { importStatementUseCase.closeOpenedStatement() }
        refreshSaved()
    }

    private companion object {
        const val SAMPLE_ROWS = 5
        const val UNLINKED_BANK = "Statement"
    }
}
