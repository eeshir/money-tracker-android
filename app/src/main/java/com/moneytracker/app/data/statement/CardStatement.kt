package com.moneytracker.app.data.statement

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The summary block of a credit card statement: what the bank says the card
 * owed on [statementDate], and its limits. Card statements carry no running
 * balance, so this — not a closing row — is what the import checks the card
 * against (#13).
 */
data class CardStatementSummary(
    val bankName: String,
    val cardLast4: String?,
    val statementDate: LocalDate,
    /** Owed on [statementDate] (the statement balance / total amount due). */
    val outstanding: BigDecimal,
    val minimumDue: BigDecimal? = null,
    val dueDate: LocalDate? = null,
    val creditLimit: BigDecimal? = null,
    /**
     * As printed. Banks count EMI principal still to be billed against the
     * limit, so `creditLimit - availableLimit` can exceed [outstanding].
     */
    val availableLimit: BigDecimal? = null
) {
    /**
     * What the card really owes against its limit on [statementDate]: `creditLimit -
     * availableLimit` when the statement prints both (it counts unbilled EMI principal,
     * as the bank's own available figure does), else [outstanding].
     */
    val owed: BigDecimal
        get() = if (creditLimit != null && availableLimit != null) (creditLimit - availableLimit).max(BigDecimal.ZERO) else outstanding
}

/** A [PdfStatementParser] for card statements, which also reads the summary block. */
interface CardStatementParser : PdfStatementParser {
    fun summary(text: String): CardStatementSummary?

    /** Reward points (or cashback) printed per transaction row, summed, for banks that print them there. */
    fun rowRewards(text: String): java.math.BigDecimal? = null

    /** The reward printed on each transaction row, for banks that print one. */
    fun rowRewardDetails(text: String): List<StatementRowReward> = emptyList()
}

/** One statement row's printed reward: [points] is negative for a credit (refund). */
data class StatementRowReward(
    val date: java.time.LocalDate, val amount: java.math.BigDecimal, val points: java.math.BigDecimal, val credit: Boolean,
    val description: String = ""
)

/** Helpers shared by the card statement readers. */
internal object CardStatementText {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    /** "1,23,456.78" (Indian or western grouping) → 123456.78. */
    fun amount(raw: String): BigDecimal = BigDecimal(raw.replace(",", ""))

    /** A statement row's date at noon IST: card statements are read as date-only. */
    fun millisAtNoon(date: LocalDate): Long =
        date.atTime(LocalTime.NOON).atZone(IST).toInstant().toEpochMilli()

    /** Trims trailing "(Ref# …)" and collapses the column padding banks print. */
    fun cleanDescription(raw: String): String =
        raw.replace(Regex("""\(Ref#[^)]*\)"""), "").replace(Regex("""\s{2,}"""), " ").trim()

    const val AMOUNT = """\d[\d,]*\.\d{2}"""
}
