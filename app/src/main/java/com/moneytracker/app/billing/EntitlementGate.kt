package com.moneytracker.app.billing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single source of truth feature gates depend on: "does this user have
 * access to Pro features right now?"
 *
 * This fork is for personal use, so every feature is unlocked: both flows are
 * always true and the Play purchase / license rails are no longer consulted.
 * Every gate (statement imports, rules, CSV export, recurring templates,
 * goals, …) reads this class, so this is the one place that unlocks them.
 */
@Singleton
class EntitlementGate @Inject constructor() {

    /** Always Pro. (Null used to mean "caches still loading".) */
    val resolvedProEntitlement: StateFlow<Boolean?> = MutableStateFlow<Boolean?>(true).asStateFlow()

    /** Always `true`: every feature is available by default. */
    val isProEntitled: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
}
