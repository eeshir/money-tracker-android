package com.moneytracker.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.moneytracker.app.data.statement.PdfPasswordRequiredException
import com.moneytracker.app.data.statement.PdfTextExtractor
import com.moneytracker.app.data.statement.SavedStatementStore
import com.moneytracker.app.data.statement.StatementPasswordStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug-only (never in release builds): writes the extracted text of saved statements to the app's private
 * `files/probe/` folder, so a statement reader can be fixed against the real layout without the PDF password
 * leaving the phone. Only statements whose text contains [FILTER] are written. Guarded by android.permission.DUMP:
 *   adb shell am broadcast -n <pkg>/com.moneytracker.app.debug.StatementTextProbeReceiver --es filter "tata neu"
 * then read with `adb shell run-as <pkg> cat files/probe/<id>.txt`.
 */
class StatementTextProbeReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Access {
        fun savedStatements(): SavedStatementStore
        fun passwords(): StatementPasswordStore
    }

    override fun onReceive(context: Context, intent: Intent) {
        val filter = intent.getStringExtra(FILTER)?.lowercase() ?: return
        val access = EntryPointAccessors.fromApplication(context.applicationContext, Access::class.java)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val out = File(context.filesDir, "probe").apply { deleteRecursively(); mkdirs() }
                val store = access.savedStatements()
                val passwords = access.passwords().all()
                val summary = StringBuilder()
                for (statement in store.list()) {
                    val text = readText(context, store.openPlain(statement.id), passwords)
                    if (text == null) { summary.appendLine("${statement.id} locked/unreadable ${statement.label}"); continue }
                    if (filter in text.lowercase()) {
                        File(out, "${statement.id}.txt").writeText(text)
                        summary.appendLine("${statement.id} MATCH ${statement.label}")
                    }
                }
                store.closePlain()
                File(out, "_summary.txt").writeText(summary.toString())
            } finally {
                pending.finish()
            }
        }
    }

    private fun readText(context: Context, file: File, passwords: Collection<String>): String? {
        val uri = Uri.fromFile(file)
        return try {
            PdfTextExtractor.extractText(context, uri, null)
        } catch (locked: PdfPasswordRequiredException) {
            passwords.firstNotNullOfOrNull { candidate ->
                try { PdfTextExtractor.extractText(context, uri, candidate) } catch (_: PdfPasswordRequiredException) { null }
            }
        } catch (e: Exception) {
            null
        }
    }

    private companion object { const val FILTER = "filter" }
}
