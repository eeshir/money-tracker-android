package com.moneytracker.app.presentation.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moneytracker.app.BuildConfig
import com.moneytracker.app.R
import com.moneytracker.app.data.update.UpdateState

/** What the update row says and does for each state; used by Settings. */
data class UpdateRowModel(
    val title: String,
    val subtitle: String,
    val progress: Float?,
    val onClick: (() -> Unit)?
)

@Composable
fun rememberUpdateRow(viewModel: UpdateViewModel = hiltViewModel()): UpdateRowModel {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val version = BuildConfig.VERSION_NAME
    if (!viewModel.canSelfUpdate) {
        return UpdateRowModel(
            stringResource(R.string.update_title),
            stringResource(R.string.update_unavailable, version),
            null, null
        )
    }
    return when (val s = state) {
        UpdateState.Idle, UpdateState.UpToDate -> UpdateRowModel(
            stringResource(R.string.update_title),
            stringResource(if (s == UpdateState.UpToDate) R.string.update_up_to_date else R.string.update_check_hint, version),
            null, viewModel::checkNow
        )
        UpdateState.Checking -> UpdateRowModel(stringResource(R.string.update_title), stringResource(R.string.update_checking), 0f, null)
        is UpdateState.Available -> UpdateRowModel(
            stringResource(R.string.update_available_title, s.release.version),
            stringResource(R.string.update_tap_to_download),
            null, viewModel::download
        )
        is UpdateState.Downloading -> UpdateRowModel(
            stringResource(R.string.update_available_title, s.release.version),
            stringResource(R.string.update_downloading),
            s.progress, null
        )
        is UpdateState.ReadyToInstall -> UpdateRowModel(
            stringResource(R.string.update_ready_title, s.release.version),
            stringResource(R.string.update_tap_to_install),
            null, viewModel::install
        )
        is UpdateState.Failed -> UpdateRowModel(
            stringResource(R.string.update_title),
            stringResource(
                when (s.reason) {
                    UpdateState.Reason.NETWORK -> R.string.update_failed_network
                    UpdateState.Reason.NO_RELEASE -> R.string.update_failed_no_release
                    UpdateState.Reason.NO_HASH -> R.string.update_failed_no_hash
                    UpdateState.Reason.BAD_HASH -> R.string.update_failed_bad_hash
                    UpdateState.Reason.WRONG_APP, UpdateState.Reason.WRONG_SIGNATURE -> R.string.update_failed_signature
                }
            ),
            null, viewModel::checkNow
        )
    }
}
