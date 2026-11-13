package com.moneytracker.app.data.rewards

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Which Card rewards screen the person last used: the simple one (default) or the advanced one. Plain preferences. */
@Singleton
class RewardsUiPrefs @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("card_rewards_ui", Context.MODE_PRIVATE)
    private val _advanced = MutableStateFlow(prefs.getBoolean(KEY, false))
    val advanced: StateFlow<Boolean> = _advanced.asStateFlow()

    fun setAdvanced(value: Boolean) {
        prefs.edit().putBoolean(KEY, value).apply()
        _advanced.value = value
    }

    private companion object { const val KEY = "advanced" }
}
