package com.pennywiseai.tracker.data.statement.gmail

import com.pennywiseai.tracker.data.statement.ImportStatementUseCase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports the fetched statements without anyone tapping through them: exactly
 * what the manual flow does with its defaults — every new row kept, a new card
 * created from its first statement, the card's limit and balance applied (the
 * rules in [ImportStatementUseCase.applyBalance] keep that safe to repeat).
 * Statements that are still locked or unreadable are left for the person.
 */
@Singleton
class StatementAutoImporter @Inject constructor(
    private val importStatement: ImportStatementUseCase,
    private val sync: GmailStatementSync
) {
    data class Outcome(val imported: Int, val needPassword: Int, val failed: Int)

    suspend fun run(all: Boolean = false): Outcome {
        var imported = 0
        var needPassword = 0
        var failed = 0
        // Oldest first, so the newest statement is the last one applied to its card.
        for (statement in (if (all) sync.allUnimported() else sync.waiting()).sortedBy { it.sortMillis }) {
            when (val read = importStatement.readPdf(importStatement.savedStatementUri(statement.id))) {
                is ImportStatementUseCase.PdfRead.Parsed -> try {
                    val account = importStatement.resolveAccount(read.transactions)
                        ?: read.cardSummary?.let { importStatement.ensureCardAccount(it) }
                    val plan = importStatement.preview(read.transactions)
                    importStatement.commit(read.transactions, plan.newIndices.toSet())
                    importStatement.markStatementImported(statement.id)
                    if (account != null) importStatement.applyBalance(read.transactions, account, read.cardSummary)
                    imported++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                }
                is ImportStatementUseCase.PdfRead.PasswordRequired -> needPassword++
                is ImportStatementUseCase.PdfRead.Error -> failed++
            }
        }
        importStatement.closeOpenedStatement()
        return Outcome(imported, needPassword, failed)
    }
}
