package com.pennywiseai.tracker.presentation.split

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pennywiseai.tracker.data.database.entity.LoanDirection
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import com.pennywiseai.tracker.data.repository.LoanRepository
import com.pennywiseai.tracker.domain.loan.PersonSplitTotal
import com.pennywiseai.tracker.domain.loan.SplitSummary
import com.pennywiseai.tracker.domain.loan.UpiRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Splits tab: what each person owes you across all your split bills, as one sum. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SplitsViewModel @Inject constructor(
    private val loanRepository: LoanRepository,
    private val preferences: UserPreferencesRepository
) : ViewModel() {

    /** Null until the first load, so the empty state doesn't flash. */
    val people: StateFlow<List<PersonSplitTotal>?> = loanRepository.getActiveLoans()
        .flatMapLatest { loans ->
            val lent = loans.filter { it.direction == LoanDirection.LENT }
            if (lent.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(lent.map { loan -> loanRepository.getTransactionsForLoan(loan.id).map { loan to it } }) { rows ->
                    SplitSummary.compile(rows.toList())
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val upiId: StateFlow<String?> = preferences.upiId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Your name for the UPI request, unless it's still the default. */
    val payeeName: StateFlow<String?> = preferences.userPreferences
        .map { prefs -> prefs.userName.trim().takeIf { it.isNotEmpty() && it != "User" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Marks everything this person owes as paid. Their bills stay out of your spend. */
    fun settle(loanIds: List<Long>) {
        viewModelScope.launch { loanRepository.settleLoans(loanIds) }
    }

    /** Removes the split debts entirely: the bills count as your own spend again. */
    fun delete(loanIds: List<Long>) {
        viewModelScope.launch { loanIds.forEach { loanRepository.deleteLoan(it) } }
    }

    /** Saves [id] as your UPI ID; false (and nothing saved) when it isn't name@handle. */
    fun saveUpiId(id: String): Boolean {
        if (!UpiRequest.isValidUpiId(id)) return false
        viewModelScope.launch { preferences.setUpiId(id) }
        return true
    }
}
