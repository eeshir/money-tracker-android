package com.pennywiseai.tracker.data.statement

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** IDBI rows carry the transaction's time, so the import can match them like SMS. */
internal const val IDBI_PDF_SENDER = "IDBI PDF"

/**
 * IDBI Bank savings-account statement. After text extraction each row opens with
 * its serial number, date and time and value date; the description wraps onto
 * the next lines, and the row closes on a line ending "amount balance"
 * (figures illustrative):
 * ```
 * STATEMENT OF ACCOUNT :1234567890001234
 * 1 05/10/2026 10:15:00 05/10/2026 UPI/111122223333/Sample
 * Payee Store
 * 40.00 460.00
 * 2 01/10/2026 09:00:00 01/10/2026 NEFT-ABCD123456789-SAMPLE EMPLOYER 500.00 500.00
 * Dr Count Cr Count Debits Credits
 * 1 1 40.0 500.0
 * ```
 * The withdrawal and deposit columns don't survive extraction, so the direction
 * is read from how the running balance moved. Rows run newest first; the oldest
 * row is settled against the opening balance the summary's totals imply.
 */
class IdbiBankPdfParser : PdfStatementParser {

    override fun canHandle(text: String): Boolean {
        val lower = text.lowercase()
        return "ibkl0" in lower && "statement of account" in lower && "txn date" in lower
    }

    override fun parse(text: String): List<ParsedTransaction> {
        val account = (STATEMENT_ACCOUNT.find(text) ?: ACCOUNT_NO.find(text))?.groupValues?.get(1)?.takeLast(4)
        val found = findRows(text.lines().map { it.trim() }.filter { it.isNotEmpty() })
        if (found.isEmpty()) return emptyList()
        // Oldest first, so each row's balance follows the one before it. IDBI prints
        // newest first; a one-day statement can't tell, so it's taken as printed that way.
        val rows = if (found.first().dateTime >= found.last().dateTime) found.reversed() else found

        var previousBalance = openingBalance(text, rows.last().balance)
        return rows.map { row ->
            val type = typeOf(row, previousBalance)
            previousBalance = row.balance
            ParsedTransaction(
                amount = row.amount,
                type = type,
                merchant = merchantFrom(row.description),
                reference = referenceFrom(row.description),
                accountLast4 = account,
                // The running balance: lets the import check the closing figure (#13).
                balance = row.balance,
                // Date, time and running balance lead the text: the import's duplicate
                // hash is built from it, and the balance makes every row unique.
                smsBody = "${row.dateTime.format(DATE_TIME)} · balance ${row.balance.toPlainString()}\n${row.description}",
                sender = IDBI_PDF_SENDER,
                timestamp = row.dateTime.atZone(IST).toInstant().toEpochMilli(),
                bankName = BANK_NAME
            )
        }
    }

    private data class Row(
        val dateTime: LocalDateTime,
        val description: String,
        val amount: BigDecimal,
        val balance: BigDecimal
    )

    /**
     * A row opens on its "serial date time value-date" line and closes on the
     * first line ending "amount balance": that same line, or one after the
     * wrapped description. Page footers and headers in between are skipped.
     */
    private fun findRows(lines: List<String>): List<Row> {
        val rows = mutableListOf<Row>()
        var open: Pair<MatchResult, MutableList<String>>? = null
        for (line in lines) {
            val start = ROW_START.matchEntire(line)
            val (head, description) = if (start != null) {
                (start to mutableListOf<String>()).also { open = it }
            } else {
                open ?: continue
            }
            val rest = if (start != null) start.groupValues[5] else line
            if (start == null && NOISE.any { it.containsMatchIn(line) }) continue
            val tail = TAIL.find(rest)
            if (tail == null) {
                if (rest.isNotEmpty()) description += rest
                continue
            }
            rest.substring(0, tail.range.first).trim().takeIf { it.isNotEmpty() }?.let { description += it }
            val dateTime = runCatching {
                LocalDateTime.parse("${head.groupValues[2]} ${head.groupValues[3]}", DATE_TIME)
            }.getOrNull()
            if (dateTime != null) {
                rows += Row(
                    dateTime = dateTime,
                    description = description.joinToString(" "),
                    amount = money(tail.groupValues[1]),
                    balance = money(tail.groupValues[2])
                )
            }
            open = null
        }
        return rows
    }

    /**
     * The balance before the oldest row: the newest balance less the summary's
     * credits plus its debits. The summary rounds to one decimal, so the oldest
     * row's direction is settled by whichever reading lands nearer.
     */
    private fun openingBalance(text: String, newestBalance: BigDecimal): BigDecimal? {
        val totals = TOTALS.find(text) ?: return null
        return newestBalance - money(totals.groupValues[2]) + money(totals.groupValues[1])
    }

    private fun typeOf(row: Row, previousBalance: BigDecimal?): TransactionType {
        previousBalance ?: return guessType(row.description)
        val ifDebit = (previousBalance - row.amount - row.balance).abs()
        val ifCredit = (previousBalance + row.amount - row.balance).abs()
        return when {
            ifDebit.signum() == 0 -> TransactionType.EXPENSE
            ifCredit.signum() == 0 -> TransactionType.INCOME
            minOf(ifDebit, ifCredit) <= ROUNDING -> if (ifDebit <= ifCredit) TransactionType.EXPENSE else TransactionType.INCOME
            else -> guessType(row.description)
        }
    }

    private fun merchantFrom(description: String): String? {
        CHANNEL_PAYEE.find(description)?.let(::firstGroup)?.trim()?.trimEnd('-', ' ')?.takeIf(String::isNotEmpty)
            ?.let { return it.take(MAX_MERCHANT) }
        return when {
            description.startsWith("Int.", ignoreCase = true) -> "Interest"
            description.startsWith("SMS_CHARGE", ignoreCase = true) -> "SMS charges"
            // "BO<branch>/<date time>/<entry>": a deposit or withdrawal made at a branch.
            BRANCH_ENTRY.containsMatchIn(description) -> "Branch transaction"
            else -> description.take(MAX_MERCHANT).trim().ifEmpty { null }
        }
    }

    private fun referenceFrom(description: String): String? =
        CHANNEL_REFERENCE.find(description)?.let(::firstGroup)

    /** The one alternative of a "this|that" pattern that matched. */
    private fun firstGroup(match: MatchResult): String? = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }

    /** Only for a row whose direction the balance can't settle (no summary to give the opening). */
    private fun guessType(description: String): TransactionType {
        val text = description.lowercase()
        return if (INCOME_HINTS.any { it in text }) TransactionType.INCOME else TransactionType.EXPENSE
    }

    private fun money(raw: String) = BigDecimal(raw.replace(",", ""))

    private companion object {
        /** Matches the SMS parser's bank name so rows land on the SMS-tracked account. */
        const val BANK_NAME = "IDBI Bank"
        const val MAX_MERCHANT = 40
        val ROUNDING = BigDecimal("0.10")
        val IST: ZoneId = ZoneId.of("Asia/Kolkata")
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
        const val AMOUNT = """\d[\d,]*\.\d{1,2}"""
        val STATEMENT_ACCOUNT = Regex("""STATEMENT OF ACCOUNT\s*:\s*(\d{6,})""", RegexOption.IGNORE_CASE)
        val ACCOUNT_NO = Regex("""Account No\s*:\s*(\d{6,})""", RegexOption.IGNORE_CASE)
        // "12 05/10/2026 10:15:00 05/10/2026 <description…>"
        val ROW_START = Regex("""^(\d+)\s+(\d{2}/\d{2}/\d{4})\s+(\d{2}:\d{2}:\d{2})\s+(\d{2}/\d{2}/\d{4})\s*(.*)$""")
        // "<…> amount balance" closing a row.
        val TAIL = Regex("""(?:^|\s)($AMOUNT)\s+($AMOUNT)$""")
        val TOTALS = Regex("""Dr Count\s+Cr Count\s+Debits\s+Credits\s+\d+\s+\d+\s+($AMOUNT|\d+)\s+($AMOUNT|\d+)""", RegexOption.IGNORE_CASE)
        // "UPI/<ref>/<payee>", "NEFT-<ref>-<payee>", "ACHECS-<payee>-<ref>"
        val CHANNEL_PAYEE = Regex("""^(?:UPI|IMPS)/[^/]*/([^/]+)|^(?:NEFT|RTGS)-[^-]*-([^-]+)|^ACH\w*-([^-]+)""", RegexOption.IGNORE_CASE)
        val BRANCH_ENTRY = Regex("""^BO\d+/""", RegexOption.IGNORE_CASE)
        val CHANNEL_REFERENCE =Regex("""^(?:UPI|IMPS)/(\d{6,})/|^(?:NEFT|RTGS)-(\w{6,})-""", RegexOption.IGNORE_CASE)
        val INCOME_HINTS = listOf("int.", "neft-in", "salary", "refund", "reversal", "cashback", "dividend", "fnldiv")
        val NOISE = listOf(
            Regex("""^page \d+ of \d+""", RegexOption.IGNORE_CASE),
            Regex("""^IDBI Bank Ltd\.""", RegexOption.IGNORE_CASE),
            Regex("""^Our Toll-free""", RegexOption.IGNORE_CASE),
            Regex("""^S\.No\s+Txn Date""", RegexOption.IGNORE_CASE)
        )
    }
}
