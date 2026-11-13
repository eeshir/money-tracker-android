package com.moneytracker.app.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.moneytracker.app.R
import com.moneytracker.app.data.backup.BackupOption

@StringRes
private fun BackupOption.titleRes(): Int = when (this) {
    BackupOption.RECEIPTS -> R.string.backup_option_receipts_title
    BackupOption.STATEMENTS -> R.string.backup_option_statements_title
    BackupOption.REWARDS -> R.string.backup_option_rewards_title
    BackupOption.WEBHOOKS -> R.string.backup_option_webhooks_title
}

@StringRes
private fun BackupOption.subtitleRes(): Int = when (this) {
    BackupOption.RECEIPTS -> R.string.backup_option_receipts_subtitle
    BackupOption.STATEMENTS -> R.string.backup_option_statements_subtitle
    BackupOption.REWARDS -> R.string.backup_option_rewards_subtitle
    BackupOption.WEBHOOKS -> R.string.backup_option_webhooks_subtitle
}

/**
 * Lets the person choose which optional parts go into a backup. The core data (transactions,
 * accounts, budgets, ...) is always included. [confirmLabel] is "Export" for a backup made now and
 * "Done" when only the saved choice is being edited; ticks are saved as they are made.
 */
@Composable
fun BackupContentDialog(
    title: String,
    selected: Set<BackupOption>,
    confirmLabel: String,
    onToggle: (BackupOption, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.backup_options_always_included),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                BackupOption.entries.forEach { option ->
                    val checked = option in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Checkbox) { onToggle(option, !checked) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(stringResource(option.titleRes()), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(option.subtitleRes()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}
