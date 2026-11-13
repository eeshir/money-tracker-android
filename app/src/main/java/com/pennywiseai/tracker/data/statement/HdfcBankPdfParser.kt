package com.pennywiseai.tracker.data.statement

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** HDFC account rows carry a date only; the import matches them as date-only. */
internal const val HDFC_PDF_SENDER = "HDFC PDF"

/**
 * HDFC Bank savings-account statement (not the credit-card one, which
 * [HdfcCreditCardPdfParser] reads). After text extraction a row opens on a line
 * starting with its date; the narration wraps around the line that carries
 * "reference value-date amount balance" (figures illustrative):
 * ```
 * Date Narration Chq./Ref.No. Value Dt Withdrawal Amt. Deposit Amt. Closing Balance
 * 05/10/26 UPI-SAMPLE
 * PAYEE-sample@okbank
 * 0000111122223333 05/10/26 40.00 460.00
 * BANK0000123-111122223333-UPI
 * STATEMENT SUMMARY :-
 * Opening Balance Dr Count Cr Count Debits Credits Closing Bal
 * 500.00 1 0 40.00 0.00 460.00
 * ```
 * Each page repeats the account holder's details and the bank's footer, often
 * in the middle of a row's narration, so that block is dropped first. The
 * withdrawal and deposit columns don't survive extraction: the direction is read
 * from how the running balance moved, starting from the summary's opening balance.
 */
class HdfcBankPdfParser : PdfStatementParser {

    override fun canHandle(text: String): Boolean {
        val lower = text.lowercase()
        return "hdfc bank" in lower && "statement of account" in lower &&
            "withdrawal amt" in lower && "deposit amt" in lower && "closing balance" in lower
    }

    override fun parse(text: String): List<ParsedTransaction> {
        val lines = withoutPageBlocks(text.lines().map { it.trim() }.filter { it.isNotEmpty() })
        val account = ACCOUNT_NO.find(text)?.groupValues?.get(1)?.takeLast(4)
        var previousBalance = OPENING.find(text)?.groupValues?.get(1)?.let(::money)
        return findRows(lines).map { row ->
            val type = when {
                previousBalance != null && row.balance.compareTo(previousBalance!! - row.amount) == 0 -> TransactionType.EXPENSE
                previousBalance != null && row.balance.compareTo(previousBalance!! + row.amount) == 0 -> TransactionType.INCOME
                else -> guessType(row.narration)
            }
            previousBalance = row.balance
            ParsedTransaction(
                amount = row.amount,
                type = type,
                merchant = merchantFrom(row.narration),
                reference = row.reference.trimStart('0').takeIf { it.length >= MIN_REFERENCE },
                accountLast4 = account,
                // The running balance: lets the import check the closing figure (#13).
                balance = row.balance,
                // Date and running balance lead the text: the import's duplicate hash is
                // built from it, and the balance makes every row unique.
                smsBody = "${row.date.format(DATE)} · balance ${row.balance.toPlainString()}\n${row.narration}",
                sender = HDFC_PDF_SENDER,
                timestamp = row.date.atTime(LocalTime.NOON).atZone(IST).toInstant().toEpochMilli(),
                bankName = BANK_NAME
            )
        }
    }

    private data class Row(
        val date: LocalDate,
        val narration: String,
        val reference: String,
        val amount: BigDecimal,
        val balance: BigDecimal
    )

    /**
     * Drops each page's repeated block — from "Page No .:" through the footer's
     * registered-office line — which can split one row's narration in two.
     */
    private fun withoutPageBlocks(lines: List<String>): List<String> {
        val kept = mutableListOf<String>()
        var inBlock = false
        for (line in lines) {
            when {
                line.startsWith("Page No", ignoreCase = true) -> inBlock = true
                inBlock && line.startsWith("Registered Office", ignoreCase = true) -> inBlock = false
                !inBlock -> kept += line
            }
        }
        return kept
    }

    /**
     * A row opens on a line starting with its date and takes every line up to
     * the next row's: the narration before and after its "reference value-date
     * amount balance" line. A new row can't open until the current one has that
     * line, so a wrapped narration that happens to start with a date stays put.
     */
    private fun findRows(lines: List<String>): List<Row> {
        data class Open(val date: LocalDate, val narration: MutableList<String>, var key: MatchResult? = null)

        val rows = mutableListOf<Row>()
        var open: Open? = null
        fun close() {
            val row = open ?: return
            val key = row.key ?: return
            rows += Row(
                date = row.date,
                narration = row.narration.joinToString(" "),
                reference = key.groupValues[1],
                amount = money(key.groupValues[3]),
                balance = money(key.groupValues[4])
            )
        }
        for (line in lines) {
            if (line.startsWith("STATEMENT SUMMARY", ignoreCase = true)) break
            val start = ROW_START.matchEntire(line)
            val date = start?.let { runCatching { LocalDate.parse(it.groupValues[1], DATE) }.getOrNull() }
            val current = open
            val rest = if (start != null && date != null && (current == null || current.key != null)) {
                close()
                open = Open(date, mutableListOf())
                start.groupValues[2]
            } else {
                current ?: continue
                line
            }
            val row = open!!
            val key = if (row.key == null) KEY.find(rest) else null
            if (key != null) {
                row.key = key
                rest.substring(0, key.range.first).trim().takeIf { it.isNotEmpty() }?.let { row.narration += it }
            } else if (rest.isNotEmpty()) {
                row.narration += rest
            }
        }
        close()
        return rows
    }

    private fun merchantFrom(narration: String): String? {
        val upper = narration.uppercase()
        return when {
            upper.startsWith("UPI-") -> narration.removeRange(0, 4).substringBefore('-').trim()
                .takeIf { it.isNotEmpty() && !it.all(Char::isDigit) } ?: "UPI"
            upper.startsWith("POS ") -> POS_MERCHANT.find(narration)?.groupValues?.get(1)?.trim()
            // Internet-banking bill pay to a masked card number: the person's own card bill.
            upper.startsWith("IB BILLPAY") && MASKED_CARD.containsMatchIn(narration) -> "Credit card payment"
            upper.startsWith("INTEREST") -> "Interest"
            upper.startsWith("UPIRET") -> "UPI refund"
            // "A2AINT01 -EMPLOYER NAME -SALARY -…"
            upper.startsWith("A2A") -> narration.substringAfter('-').substringBefore(" -").trim()
            // "NEFT CR-<IFSC>-<PAYER NAME>-…"
            upper.startsWith("NEFT") || upper.startsWith("RTGS") -> narration.split('-').getOrNull(2)?.trim()
            else -> null
        }?.take(MAX_MERCHANT)?.ifEmpty { null } ?: narration.take(MAX_MERCHANT).trim().ifEmpty { null }
    }

    /** Only for a row whose direction the balance can't settle (no opening balance). */
    private fun guessType(narration: String): TransactionType {
        val text = narration.lowercase()
        return if (INCOME_HINTS.any { it in text }) TransactionType.INCOME else TransactionType.EXPENSE
    }

    private fun money(raw: String) = BigDecimal(raw.replace(",", ""))

    private companion object {
        /** Matches the SMS parser's bank name so rows land on the SMS-tracked account. */
        const val BANK_NAME = "HDFC Bank"
        const val MAX_MERCHANT = 40
        /** Shorter "references" are the zero-filled placeholder some rows carry. */
        const val MIN_REFERENCE = 6
        val IST: ZoneId = ZoneId.of("Asia/Kolkata")
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yy")
        const val AMOUNT = """\d[\d,]*\.\d{2}"""
        val ACCOUNT_NO = Regex("""Account No\s*:\s*(\d{6,})""", RegexOption.IGNORE_CASE)
        val OPENING = Regex("""Opening Balance\s+Dr Count\s+Cr Count\s+Debits\s+Credits\s+Closing Bal\s+($AMOUNT)""", RegexOption.IGNORE_CASE)
        val ROW_START = Regex("""^(\d{2}/\d{2}/\d{2})\s+(.*)$""")
        // "<reference> <value date> <amount> <balance>" ending the row's key line.
        val KEY = Regex("""(\S+)\s+(\d{2}/\d{2}/\d{2})\s+($AMOUNT)\s+($AMOUNT)$""")
        // "POS <card> <auth> <ddMMMyy> <hh:mm:ss, maybe wrapped> <city> <merchant…>"
        val POS_MERCHANT = Regex("""^POS\s+\S+\s+\d+\s+\d{2}[A-Z]{3}\d{2}\s+[\d:]+(?:\s+[\d:]+)?\s+\S+\s+(.+)$""", RegexOption.IGNORE_CASE)
        val MASKED_CARD = Regex("""\d{4,6}X{4,}\d{4}""", RegexOption.IGNORE_CASE)
        val INCOME_HINTS = listOf("salary", "interest paid", "refund", "reversal", "upiret", "cashback", "neft cr")
    }
}
