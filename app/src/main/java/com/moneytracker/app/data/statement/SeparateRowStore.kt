package com.moneytracker.app.data.statement

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Statement rows the person said are transactions of their own, however much they look like a copy. They are never
 * offered or removed as duplicates, and never linked as a refund by themselves. Kept by transaction hash, which a
 * backup and restore does not change.
 */
@Singleton
class SeparateRowStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("separate_statement_rows", Context.MODE_PRIVATE)
    private val _hashes = MutableStateFlow(prefs.getStringSet(KEY, emptySet()).orEmpty().toSet())
    val hashes: StateFlow<Set<String>> = _hashes.asStateFlow()

    /** Adds to what is already kept (also how a backup is restored). */
    fun keep(hashes: Collection<String>) {
        val all = _hashes.value + hashes.filter { it.isNotBlank() }
        prefs.edit().putStringSet(KEY, all).apply()
        _hashes.value = all
    }

    private companion object { const val KEY = "hashes" }
}
