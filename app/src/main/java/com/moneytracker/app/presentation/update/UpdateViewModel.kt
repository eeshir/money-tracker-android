package com.moneytracker.app.presentation.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moneytracker.app.data.update.AppUpdater
import com.moneytracker.app.data.update.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Drives the "App updates" row in Settings and the badge on Home's settings button. */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updater: AppUpdater
) : ViewModel() {
    val state: StateFlow<UpdateState> = updater.state
    val canSelfUpdate: Boolean get() = updater.canSelfUpdate

    /** The quiet check done when a screen opens: at most one every few hours, and only for builds that can update. */
    fun checkQuietly() {
        if (updater.canSelfUpdate) viewModelScope.launch { updater.check(force = false) }
    }

    fun checkNow() { viewModelScope.launch { updater.check(force = true) } }

    fun download() { viewModelScope.launch { updater.download() } }

    fun install() { updater.install() }
}
