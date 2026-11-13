package com.moneytracker.app.presentation.statement

import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneytracker.app.R
import com.moneytracker.app.data.statement.SavedStatement
import com.moneytracker.app.data.statement.gmail.GmailAuthExpiredException
import com.moneytracker.app.data.statement.gmail.GmailAuthorization
import com.moneytracker.app.data.statement.gmail.GmailAuthorizer
import com.moneytracker.app.data.statement.gmail.GmailStatementSync
import com.moneytracker.app.data.statement.gmail.StatementAutoImporter
import com.moneytracker.app.ui.UiText
import android.content.Context
import com.moneytracker.app.data.statement.gmail.GmailSyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GmailStatementsUiState(
    val isSupported: Boolean,
    val isBusy: Boolean = false,
    /** Null until the first fetch; then the fetched statements still waiting to be imported (possibly none). */
    val statements: List<SavedStatement>? = null,
    /** Google's consent screen, waiting to be launched by the UI. */
    val consentRequest: IntentSender? = null,
    val error: UiText? = null,
    /** What the last "fetch and import" did, in words. */
    val syncOutcome: StatementAutoImporter.Outcome? = null,
    /** The daily background fetch and import is on. */
    val autoSync: Boolean = false
)

/**
 * The "From Gmail" section of statement import: signs in read-only, then one
 * tap fetches what is new — saving each PDF as it arrives and keeping only the
 * latest statement per card. The access token lives only in memory.
 */
@HiltViewModel
class GmailStatementsViewModel @Inject constructor(
    private val authorizer: GmailAuthorizer,
    private val sync: GmailStatementSync,
    private val importer: StatementAutoImporter,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        GmailStatementsUiState(isSupported = authorizer.isSupported, autoSync = GmailSyncWorker.isEnabled(context))
    )
    val uiState: StateFlow<GmailStatementsUiState> = _uiState.asStateFlow()

    private var token: String? = null

    /** Set while a "fetch and import" is running, so it carries on after a sign-in screen. */
    private var importAfterFetch = false
    private var backfillRun = false

    /** Signs in if needed, then fetches. */
    fun fetch() = start(importToo = false)

    /** Signs in if needed, fetches, then imports everything readable — the daily run, on demand. */
    fun fetchAndImport() = start(importToo = true)

    /** Like [fetchAndImport] but searches back to January and imports every statement found, not just the newest of each card. */
    fun fetchAndImportAll() = start(importToo = true, backfill = true)

    private fun start(importToo: Boolean, backfill: Boolean = false) {
        importAfterFetch = importToo
        backfillRun = backfill
        _uiState.update { it.copy(isBusy = true, error = null, syncOutcome = null) }
        viewModelScope.launch {
            val current = token ?: when (val auth = authorizer.authorize()) {
                is GmailAuthorization.Granted -> auth.accessToken
                is GmailAuthorization.NeedsConsent -> {
                    _uiState.update { it.copy(consentRequest = auth.intentSender) }
                    return@launch
                }
                GmailAuthorization.Failed -> return@launch fail(R.string.gmail_error_sign_in)
            }
            token = current
            runFetch(current)
        }
    }

    /** The UI launched [GmailStatementsUiState.consentRequest]; this is its result. */
    fun onConsentResult(data: Intent?) {
        _uiState.update { it.copy(consentRequest = null) }
        val granted = authorizer.tokenFromConsent(data) ?: return fail(R.string.gmail_error_sign_in)
        token = granted
        viewModelScope.launch { runFetch(granted) }
    }

    /** Turns the daily fetch-and-import on or off. */
    fun setAutoSync(enabled: Boolean) {
        GmailSyncWorker.setEnabled(context, enabled)
        _uiState.update { it.copy(autoSync = enabled) }
    }

    init {
        // Whatever an earlier fetch left waiting is there from the start.
        viewModelScope.launch {
            val waiting = sync.waiting()
            if (waiting.isNotEmpty()) _uiState.update { it.copy(statements = waiting) }
        }
    }

    /**
     * Re-reads what is waiting, after one was imported or deleted. Statements that
     * were locked are tried again first: opening one statement of a bank teaches
     * the app its password, which opens the bank's others.
     */
    fun refreshWaiting() {
        viewModelScope.launch {
            sync.reidentifyPending()
            val waiting = sync.waiting()
            _uiState.update { state ->
                if (state.statements != null || waiting.isNotEmpty()) state.copy(statements = waiting) else state
            }
        }
    }

    private suspend fun runFetch(current: String) {
        _uiState.update { it.copy(isBusy = true, error = null) }
        try {
            sync.fetch(current, backfill = backfillRun)
            val outcome = if (importAfterFetch) importer.run(all = backfillRun) else null
            backfillRun = false
            importAfterFetch = false
            _uiState.update { it.copy(isBusy = false, statements = sync.waiting(), syncOutcome = outcome) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GmailAuthExpiredException) {
            expire(current)
        } catch (e: Exception) {
            fail(R.string.gmail_error_search)
        }
    }

    /** Gmail rejected the token: forget it so the next tap signs in again. */
    private suspend fun expire(rejected: String) {
        token = null
        authorizer.invalidate(rejected)
        fail(R.string.gmail_error_expired)
    }

    private fun fail(message: Int) {
        _uiState.update { it.copy(isBusy = false, error = UiText.Res(message)) }
    }
}
