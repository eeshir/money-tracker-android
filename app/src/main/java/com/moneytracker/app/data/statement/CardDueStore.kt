package com.moneytracker.app.data.statement

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** What a card's latest statement says is payable, and by when. */
data class CardDue(
    val bankName: String,
    val cardLast4: String,
    val statementDate: LocalDate,
    val dueDate: LocalDate,
    /** The statement's total amount due. */
    val totalDue: BigDecimal,
    val minimumDue: BigDecimal?,
    /** The person said they paid it (e.g. from a source the app can't see). */
    val markedPaid: Boolean = false
) {
    val key: String get() = keyFor(bankName, cardLast4)

    companion object {
        fun keyFor(bankName: String, cardLast4: String) = "${bankName.trim().lowercase()}|$cardLast4"
    }
}

/**
 * The newest payment due per card, read from card statements as they are
 * imported. Kept in plain preferences (not Room): it is derived data that the
 * next statement rewrites, so it needs no migration and no backup entry.
 */
@Singleton
class CardDueStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _dues = MutableStateFlow(load())
    val dues: StateFlow<List<CardDue>> = _dues.asStateFlow()

    /** Remembers [summary]'s due, unless a newer statement's is already kept. */
    fun record(summary: CardStatementSummary) {
        val last4 = summary.cardLast4 ?: return
        val due = summary.dueDate ?: return
        val entry = CardDue(summary.bankName, last4, summary.statementDate, due, summary.outstanding, summary.minimumDue)
        val existing = _dues.value.firstOrNull { it.key == entry.key }
        if (existing != null && existing.statementDate.isAfter(entry.statementDate)) return
        // The same statement read again keeps a "paid" mark.
        val kept = if (existing?.statementDate == entry.statementDate) entry.copy(markedPaid = existing.markedPaid) else entry
        save(kept)
    }

    fun markPaid(key: String) {
        _dues.value.firstOrNull { it.key == key }?.let { save(it.copy(markedPaid = true)) }
    }

    private fun save(entry: CardDue) {
        val json = JSONObject()
            .put("bank", entry.bankName).put("last4", entry.cardLast4)
            .put("statement", entry.statementDate.toEpochDay()).put("due", entry.dueDate.toEpochDay())
            .put("total", entry.totalDue.toPlainString())
            .put("min", entry.minimumDue?.toPlainString() ?: JSONObject.NULL)
            .put("paid", entry.markedPaid)
        prefs.edit().putString(entry.key, json.toString()).apply()
        _dues.value = load()
    }

    private fun load(): List<CardDue> = prefs.all.values.mapNotNull { raw ->
        runCatching {
            val j = JSONObject(raw as String)
            CardDue(
                bankName = j.getString("bank"),
                cardLast4 = j.getString("last4"),
                statementDate = LocalDate.ofEpochDay(j.getLong("statement")),
                dueDate = LocalDate.ofEpochDay(j.getLong("due")),
                totalDue = BigDecimal(j.getString("total")),
                minimumDue = if (j.isNull("min")) null else BigDecimal(j.getString("min")),
                markedPaid = j.optBoolean("paid", false)
            )
        }.getOrNull()
    }

    private companion object {
        const val PREFS = "card_dues"
    }
}
