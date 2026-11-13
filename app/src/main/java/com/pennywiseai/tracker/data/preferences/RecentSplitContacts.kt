package com.pennywiseai.tracker.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * People recently used in a split, newest first, so they stay suggestions after
 * their loan is settled or deleted. Names only; kept on the phone in plain preferences.
 */
@Singleton
class RecentSplitContacts @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("recent_split_contacts", Context.MODE_PRIVATE)
    private val _names = MutableStateFlow(load())
    val names: StateFlow<List<String>> = _names.asStateFlow()

    /** Moves [people] to the front, most recent first, keeping at most [MAX]. */
    fun remember(people: List<String>) {
        val cleaned = people.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return
        val merged = (cleaned.reversed() + _names.value)
            .distinctBy { it.lowercase() }
            .take(MAX)
        prefs.edit().putString(KEY, merged.joinToString("\n")).apply()
        _names.value = merged
    }

    private fun load(): List<String> =
        prefs.getString(KEY, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    private companion object {
        const val KEY = "names"
        const val MAX = 30
    }
}
