package com.moneytracker.app.presentation.transactions

import androidx.lifecycle.ViewModel
import com.moneytracker.app.billing.EntitlementGate
import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.export.CsvExporter
import com.moneytracker.app.data.export.ExportResult
import com.moneytracker.app.data.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class ExportViewModel @Inject constructor(
    private val csvExporter: CsvExporter,
    private val userPreferencesRepository: UserPreferencesRepository,
    entitlementGate: EntitlementGate,
) : ViewModel() {

    /**
     * Pro entitlement. Free users are capped at
     * [com.moneytracker.app.billing.FreeTierLimits.MAX_CSV_EXPORT_ROWS_PER_MONTH]
     * rows per export; the dialog checks this before calling
     * [exportTransactions].
     */
    val isProEntitled: StateFlow<Boolean> = entitlementGate.isProEntitled

    fun exportTransactions(
        transactions: List<TransactionEntity>,
        fileName: String? = null
    ): Flow<ExportResult> {
        return csvExporter.exportTransactions(transactions, fileName)
    }

}
