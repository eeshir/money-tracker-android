package com.moneytracker.app.presentation.payees

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneytracker.app.data.payees.OwnPayeeMirror
import com.moneytracker.app.data.payees.OwnPayeeRule
import com.moneytracker.app.data.payees.OwnPayeeRuleStore
import com.moneytracker.app.data.repository.AccountBalanceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** An account a rule can name. */
data class AccountOption(val bank: String, val last4: String, val label: String)

data class OwnPayeesUiState(
    val rules: List<OwnPayeeRule> = emptyList(),
    val accounts: List<AccountOption> = emptyList(),
    val busy: Boolean = false,
    /** What the last run did, to be shown once. */
    val result: OwnPayeeMirror.Outcome? = null
)

@HiltViewModel
class OwnPayeesViewModel @Inject constructor(
    private val store: OwnPayeeRuleStore,
    private val mirror: OwnPayeeMirror,
    private val accountBalances: AccountBalanceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OwnPayeesUiState())
    val uiState: StateFlow<OwnPayeesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val accounts = withContext(Dispatchers.IO) {
                accountBalances.getAllLatestBalancesOnce().map { AccountOption(it.bankName, it.accountLast4, it.displayLabel) }
            }
            _uiState.update { it.copy(accounts = accounts, rules = store.all()) }
        }
    }

    /** Saves a rule and applies the rules at once, so the effect is visible. */
    fun add(payee: String, from: AccountOption?, to: AccountOption?) {
        if (payee.isBlank()) return
        store.add(
            OwnPayeeRule(
                payee = payee,
                sourceBank = from?.bank, sourceLast4 = from?.last4,
                targetBank = to?.bank, targetLast4 = to?.last4
            )
        )
        _uiState.update { it.copy(rules = store.all()) }
        applyNow()
    }

    fun remove(rule: OwnPayeeRule) {
        store.remove(rule.id)
        _uiState.update { it.copy(rules = store.all()) }
    }

    fun applyNow() {
        _uiState.update { it.copy(busy = true, result = null) }
        viewModelScope.launch {
            val outcome = runCatching { mirror.applyAll() }.getOrNull()
            _uiState.update { it.copy(busy = false, result = outcome) }
        }
    }
}
