package com.pennywiseai.tracker.presentation.payees

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pennywiseai.tracker.R
import com.pennywiseai.tracker.data.payees.OwnPayeeRule
import com.pennywiseai.tracker.ui.components.PennyWiseScaffold
import com.pennywiseai.tracker.ui.theme.Dimensions
import com.pennywiseai.tracker.ui.theme.Spacing

@Composable
fun OwnPayeesScreen(
    onNavigateBack: () -> Unit,
    viewModel: OwnPayeesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }

    PennyWiseScaffold(
        title = stringResource(R.string.own_payees_title),
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.import_statement_back))
            }
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = Dimensions.Padding.content, end = Dimensions.Padding.content,
                top = padding.calculateTopPadding() + Dimensions.Padding.content,
                bottom = padding.calculateBottomPadding() + Dimensions.Padding.content
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                Text(
                    stringResource(R.string.own_payees_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.rules.isEmpty()) {
                item { Text(stringResource(R.string.own_payees_empty), style = MaterialTheme.typography.bodyMedium) }
            }
            items(state.rules, key = { it.id }) { rule ->
                ListItem(
                    headlineContent = { Text(describe(rule, state.accounts)) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.remove(rule) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.own_payees_delete))
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { adding = true }) { Text(stringResource(R.string.own_payees_add)) }
                    OutlinedButton(onClick = viewModel::applyNow, enabled = !state.busy && state.rules.isNotEmpty()) {
                        Text(stringResource(R.string.own_payees_apply))
                    }
                }
            }
            state.result?.let { result ->
                item { Text(stringResource(R.string.own_payees_result, result.paired, result.created)) }
            }
        }
    }

    if (adding) {
        AddPayeeDialog(
            accounts = state.accounts,
            onSave = { payee, from, to ->
                viewModel.add(payee, from, to)
                adding = false
            },
            onDismiss = { adding = false }
        )
    }
}

private fun accountLabel(bank: String?, last4: String?, accounts: List<AccountOption>): String? =
    if (bank == null || last4 == null) null
    else accounts.firstOrNull { it.bank.equals(bank, ignoreCase = true) && it.last4 == last4 }?.label ?: "$bank ••$last4"

@Composable
private fun describe(rule: OwnPayeeRule, accounts: List<AccountOption>): String {
    val to = accountLabel(rule.targetBank, rule.targetLast4, accounts) ?: stringResource(R.string.own_payees_pair_only)
    val from = accountLabel(rule.sourceBank, rule.sourceLast4, accounts)
    return if (from == null) stringResource(R.string.own_payees_rule_any, rule.payee, to)
    else stringResource(R.string.own_payees_rule_from, rule.payee, from, to)
}

@Composable
private fun AddPayeeDialog(
    accounts: List<AccountOption>,
    onSave: (String, AccountOption?, AccountOption?) -> Unit,
    onDismiss: () -> Unit
) {
    var payee by remember { mutableStateOf("") }
    var from by remember { mutableStateOf<AccountOption?>(null) }
    var to by remember { mutableStateOf<AccountOption?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.own_payees_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedTextField(
                    value = payee,
                    onValueChange = { payee = it },
                    label = { Text(stringResource(R.string.own_payees_field_payee)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                AccountPicker(stringResource(R.string.own_payees_field_from), accounts, from, stringResource(R.string.own_payees_any_account)) { from = it }
                AccountPicker(stringResource(R.string.own_payees_field_to), accounts, to, stringResource(R.string.own_payees_pair_only)) { to = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(payee, from, to) }, enabled = payee.isNotBlank()) {
                Text(stringResource(R.string.own_payees_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.own_payees_cancel)) } }
    )
}

@Composable
private fun AccountPicker(
    label: String,
    accounts: List<AccountOption>,
    selected: AccountOption?,
    noneLabel: String,
    onSelect: (AccountOption?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.label ?: noneLabel)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(noneLabel) }, onClick = { onSelect(null); open = false })
            accounts.forEach { account ->
                DropdownMenuItem(text = { Text(account.label) }, onClick = { onSelect(account); open = false })
            }
        }
    }
}
