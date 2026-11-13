package com.moneytracker.app.presentation.split

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moneytracker.app.R
import com.moneytracker.app.data.share.UpiQr
import com.moneytracker.app.domain.loan.PersonSplitTotal
import com.moneytracker.app.domain.loan.SplitSummary
import com.moneytracker.app.domain.loan.UpiRequest
import com.moneytracker.app.ui.components.PennyWiseScaffold
import com.moneytracker.app.utils.CurrencyFormatter
import com.moneytracker.app.utils.Money
import com.moneytracker.app.utils.sumByCurrency
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

private val LINE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

/** Splitwise-style tab: one total per person across all split bills, one request for the sum. */
@Composable
fun SplitsScreen(viewModel: SplitsViewModel = hiltViewModel()) {
    val people by viewModel.people.collectAsStateWithLifecycle()
    val upiId by viewModel.upiId.collectAsStateWithLifecycle()
    val payeeName by viewModel.payeeName.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askUpiFor by remember { mutableStateOf<PersonSplitTotal?>(null) }
    var confirmSettle by remember { mutableStateOf<PersonSplitTotal?>(null) }
    var confirmDelete by remember { mutableStateOf<PersonSplitTotal?>(null) }

    fun send(owed: PersonSplitTotal, id: String?) {
        val amount = CurrencyFormatter.formatCurrency(owed.total, owed.currency)
        // UPI only carries rupees; other currencies get the itemised text alone.
        val uri = if (id != null && owed.currency.equals("INR", ignoreCase = true)) {
            UpiRequest.payUri(id, payeeName, owed.total, "Split dues")
        } else {
            null
        }
        val text = SplitSummary.requestText(owed, uri, id.takeIf { uri != null })
        val title = context.getString(R.string.split_request, amount)
        scope.launch {
            runCatching {
                if (uri != null) {
                    context.startActivity(UpiQr.shareIntent(context, uri, text, title))
                } else {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(send, title))
                }
            }.onFailure { Toast.makeText(context, R.string.split_request_failed, Toast.LENGTH_SHORT).show() }
        }
    }

    PennyWiseScaffold(title = stringResource(R.string.nav_splits)) { padding ->
        val list = people
        when {
            list == null -> Unit
            list.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(stringResource(R.string.splits_empty), style = MaterialTheme.typography.bodyLarge)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 120.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "summary") {
                    val totals: Map<String, Money> = list.sumByCurrency({ it.currency }, { it.total })
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.splits_total_owed), style = MaterialTheme.typography.labelLarge)
                            Text(CurrencyFormatter.formatByCurrency(totals), style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }
                items(list, key = { it.key }) { owed ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(owed.person, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                Text(
                                    CurrencyFormatter.formatCurrency(owed.total, owed.currency),
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }
                            owed.lines.forEach { line ->
                                Row {
                                    Text(
                                        "${line.title} · ${line.date.format(LINE_DATE)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        CurrencyFormatter.formatCurrency(line.amount, owed.currency),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            Button(
                                onClick = {
                                    val inr = owed.currency.equals("INR", ignoreCase = true)
                                    if (inr && upiId == null) askUpiFor = owed else send(owed, upiId)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    stringResource(
                                        R.string.split_request,
                                        CurrencyFormatter.formatCurrency(owed.total, owed.currency)
                                    )
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = { confirmSettle = owed }, modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.splits_mark_settled))
                                }
                                OutlinedButton(
                                    onClick = { confirmDelete = owed },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text(stringResource(R.string.splits_delete))
                                }
                            }
                        }
                    }
                }
                item(key = "how") {
                    Text(
                        stringResource(R.string.splits_how_it_resolves),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    confirmSettle?.let { owed ->
        AlertDialog(
            onDismissRequest = { confirmSettle = null },
            title = { Text(stringResource(R.string.splits_settle_title, owed.person)) },
            text = { Text(stringResource(R.string.splits_settle_message, CurrencyFormatter.formatCurrency(owed.total, owed.currency))) },
            confirmButton = {
                TextButton(onClick = { viewModel.settle(owed.loanIds); confirmSettle = null }) {
                    Text(stringResource(R.string.splits_mark_settled))
                }
            },
            dismissButton = { TextButton(onClick = { confirmSettle = null }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
    confirmDelete?.let { owed ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.splits_delete_title, owed.person)) },
            text = { Text(stringResource(R.string.splits_delete_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(owed.loanIds); confirmDelete = null }) {
                    Text(stringResource(R.string.splits_delete))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }

    askUpiFor?.let { owed ->
        UpiIdDialog(
            initial = "",
            onDismiss = { askUpiFor = null },
            onSave = { id ->
                viewModel.saveUpiId(id).also { saved ->
                    if (saved) {
                        askUpiFor = null
                        send(owed, id.trim())
                    }
                }
            }
        )
    }
}
