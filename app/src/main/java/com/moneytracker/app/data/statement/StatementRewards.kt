package com.moneytracker.app.data.statement

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a card statement says it earned in its cycle. [earned] is null when no total could be found,
 * in which case [lines] (the statement lines that mention rewards) show what the statement does say,
 * so the reader can be fixed for that bank.
 */
@Serializable
data class StatementRewardEntry(
    val bankName: String,
    val cardLast4: String,
    /** The statement date, as an epoch day. */
    val statementDay: Long,
    /** As printed: points, or an amount when [isPoints] is false (cashback). */
    val earned: String? = null,
    val isPoints: Boolean = true,
    /** The figure is cashback credited in this statement for the cycle before it (HSBC credits a month later). */
    val forPreviousCycle: Boolean = false,
    val lines: List<String> = emptyList(),
    /** The reward printed on each transaction row, where the bank prints one (ICICI). */
    val rows: List<RowRewardEntry> = emptyList()
) {
    val key: String get() = "${bankName.trim().lowercase()}|$cardLast4|$statementDay"
    val statementDate: LocalDate get() = LocalDate.ofEpochDay(statementDay)
}

@Serializable
data class RowRewardEntry(
    val day: Long, val amount: String, val points: String, val credit: Boolean, val desc: String = "",
    /** The statement (as an epoch day) this row was printed on; a purchase can post a day after its cycle closes. */
    val statement: Long = 0
)

/**
 * Finds the rewards earned in a card statement's text. Banks print this differently and the layouts
 * here are best guesses, so every statement that is read leaves its reward-related lines behind (see
 * [StatementRewardEntry.lines]) for checking.
 */
internal object StatementRewardText {

    private val NUMBER = Regex("""\d[\d,]*(?:\.\d+)?""")
    private val MENTIONS = Regex("""(?i)reward|cash\s?back|points?\b|earn""")
    private val LABEL = Regex("""(?i)opening|earn\w*|redeem\w*|expir\w*|adjust\w*|closing|available|bonus|lapse\w*""")

    private val SENTENCES = listOf(
        Regex("""(?i)(?:total\s+)?(?:reward\s+)?points?\s+earned[^\d\n]{0,40}(\d[\d,]*(?:\.\d+)?)"""),
        Regex("""(?i)cash\s?back\s+earned[^\d\n]{0,40}(\d[\d,]*(?:\.\d+)?)"""),
        Regex("""(?i)earned\s+(?:this|in\s+this|during\s+(?:the\s+)?)\s*(?:statement|month|cycle|period)[^\d\n]{0,40}(\d[\d,]*(?:\.\d+)?)""")
    )

    /** The statement lines that talk about rewards, trimmed, for diagnosis. */
    fun relevantLines(text: String): List<String> {
        val all = text.lines().map { it.trim().replace(Regex("""\s{2,}"""), " ") }.filter { it.isNotEmpty() }
        val picked = LinkedHashSet<String>()
        for ((i, line) in all.withIndex()) {
            if (line.length > 160 || !MENTIONS.containsMatchIn(line)) continue
            picked += line
            // The figures often sit on the line after their label.
            all.getOrNull(i + 1)?.takeIf { it.length <= 160 }?.let { picked += it }
            // A bare heading (ICICI "EARNINGS") keeps its figures further down, so take a wider window.
            if (line.equals("EARNINGS", ignoreCase = true)) picked += all.drop(i + 2).take(8).filter { it.length <= 160 }
        }
        return picked.take(40)
    }

    private val CASHBACK_CREDIT = Regex("""(?im)^\s*\d{2}[A-Z]{3}\s+CASHBACK CREDIT\s+(\d[\d,]*\.\d{2})\s*CR""")

    /** Cashback credited as rows in the statement (HSBC): their sum, or null when there are none. */
    fun cashbackCredits(text: String): String? =
        CASHBACK_CREDIT.findAll(text).map { it.groupValues[1].replace(",", "").toBigDecimal() }.toList()
            .takeIf { it.isNotEmpty() }?.fold(java.math.BigDecimal.ZERO) { a, b -> a + b }?.toPlainString()

    /** (earned figure, true when it is points rather than a cashback amount), or null when none is found. */
    fun earned(text: String): Pair<String, Boolean>? {
        for (p in SENTENCES) {
            val m = p.find(text) ?: continue
            val isPoints = !m.value.contains("cash", ignoreCase = true)
            return m.groupValues[1].replace(",", "") to isPoints
        }
        // A table: "Opening balance  Earned  Redeemed  Closing" over a row of figures.
        val lines = text.lines()
        for ((i, header) in lines.withIndex()) {
            val labels = LABEL.findAll(header).map { it.value.lowercase() }.toList()
            if (labels.size < 3 || labels.none { it.startsWith("earn") } || labels.none { it.startsWith("open") }) continue
            val row = lines.drop(i + 1).take(2).firstOrNull { NUMBER.findAll(it).count() == labels.size } ?: continue
            val figure = NUMBER.findAll(row).map { it.value }.toList()[labels.indexOfFirst { it.startsWith("earn") }]
            // The table's Earned already includes the bonus points listed beneath it (its Total equals Earned).
            val total = figure.replace(",", "").toBigDecimalOrNull() ?: continue
            return total.toPlainString() to !header.contains("cash", ignoreCase = true)
        }
        return null
    }

    fun read(
        text: String, summary: CardStatementSummary?, rowRewards: java.math.BigDecimal? = null,
        rows: List<StatementRowReward> = emptyList()
    ): StatementRewardEntry? {
        val last4 = summary?.cardLast4 ?: return null
        val credits = cashbackCredits(text)
        val found = if (credits != null) credits to false else earned(text) ?: rowRewards?.let { it.toPlainString() to true }
        return StatementRewardEntry(
            bankName = summary.bankName, cardLast4 = last4, statementDay = summary.statementDate.toEpochDay(),
            earned = found?.first, isPoints = found?.second ?: true, forPreviousCycle = credits != null, lines = relevantLines(text),
            rows = rows.map { RowRewardEntry(it.date.toEpochDay(), it.amount.stripTrailingZeros().toPlainString(), it.points.toPlainString(), it.credit, it.description, summary.statementDate.toEpochDay()) }
        )
    }
}

/** The reward figures read from card statements, kept in plain preferences (derived data, no migration). */
@Singleton
class StatementRewardStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("statement_rewards", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<StatementRewardEntry>> = _entries.asStateFlow()

    fun has(key: String) = _entries.value.any { it.key == key }

    @Synchronized
    fun record(entry: StatementRewardEntry) {
        val next = _entries.value.filterNot { it.key == entry.key } + entry
        prefs.edit().putString(KEY, json.encodeToString(next)).apply()
        _entries.value = next
    }

    private fun load(): List<StatementRewardEntry> =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString<List<StatementRewardEntry>>(it) }.getOrNull() }.orEmpty()

    private companion object { const val KEY = "entries_v11" }
}
