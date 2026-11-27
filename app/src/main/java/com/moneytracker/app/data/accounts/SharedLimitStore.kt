package com.moneytracker.app.data.accounts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The banks whose cards the person said share one credit limit (see [SharedLimits]). */
@Singleton
class SharedLimitStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("shared_card_limits", Context.MODE_PRIVATE)
    private val _banks = MutableStateFlow(prefs.getStringSet(KEY, emptySet()).orEmpty().toSet())
    val banks: StateFlow<Set<String>> = _banks.asStateFlow()

    fun setShared(bankName: String, shared: Boolean) {
        val key = SharedLimits.bankKey(bankName)
        save(if (shared) _banks.value + key else _banks.value - key)
    }

    /** From a backup: adds to what is switched on here. */
    fun restore(banks: List<String>) {
        if (banks.isNotEmpty()) save(_banks.value + banks.map(SharedLimits::bankKey))
    }

    private fun save(banks: Set<String>) {
        prefs.edit().putStringSet(KEY, banks).apply()
        _banks.value = banks
    }

    private companion object { const val KEY = "banks" }
}
