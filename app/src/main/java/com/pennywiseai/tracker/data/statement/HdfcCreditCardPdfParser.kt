package com.pennywiseai.tracker.data.statement

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType
import com.pennywiseai.tracker.data.statement.CardStatementText.AMOUNT
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** HDFC card rows carry a date and a time that is often 00:00; the import matches them as date-only. */
internal const val HDFC_CC_PDF_SENDER = "HDFC CC PDF"

/**
 * HDFC Bank credit card statement. After text extraction (figures illustrative):
 * ```
 * DUE DATE
 * 06 Nov, 2026
 * 17/09/2026| 00:00 SOME MERCHANT (Ref# 0123)  C 32.58 l
 * 18/09/2026| 20:11 CC PAYMENT … (Ref# 0456) +  C 3,000.00 l
 * CURRENT DUES
 * C2,001.12
 * AVAILABLE CREDIT LIMIT AVAILABLE CASH LIMIT
 * C50,000 C40,000 C20,000
 * 4632XXXXXXXX1234 …  18 Sep, 2026 - 17 Oct, 2026
 * ```
 * The rupee sign comes out as a stray glyph ("C"), so amounts are read after
 * any one non-digit. A "+" before the amount marks a payment or refund.
 */
class HdfcCreditCardPdfParser : CardStatementParser {

    override fun canHandle(text: String): Boolean {
        val lower = text.lowercase()
        return "hdfc bank credit cards" in lower && "credit card statement" in lower
    }

    override fun parse(text: String): List<ParsedTransaction> {
        val card = cardLast4(text)
        return text.lines().mapNotNull { line ->
            val m = ROW.matchEntire(line.trim()) ?: return@mapNotNull null
            val (dateRaw, time, descriptionRaw, plus, amountRaw) = m.destructured
            val date = LocalDate.parse(dateRaw, ROW_DATE)
            val description = CardStatementText.cleanDescription(descriptionRaw)
            val amount = CardStatementText.amount(amountRaw)
            ParsedTransaction(
                amount = amount,
                type = if (plus.isNotBlank()) TransactionType.INCOME else TransactionType.CREDIT,
                merchant = description,
                reference = REFERENCE.find(descriptionRaw)?.groupValues?.get(1),
                accountLast4 = card,
                balance = null,
                smsBody = "$dateRaw $time · card $card · $description · ${amount.toPlainString()}",
                sender = HDFC_CC_PDF_SENDER,
                timestamp = CardStatementText.millisAtNoon(date),
                bankName = BANK_NAME,
                isFromCard = true
            )
        }
    }

    override fun summary(text: String): CardStatementSummary? {
        val statementDate = PERIOD.find(text)?.groupValues?.get(2)?.let { LocalDate.parse(it, SUMMARY_DATE) } ?: return null
        val outstanding = (CURRENT_DUES.find(text) ?: TOTAL_DUE.find(text))?.groupValues?.get(1) ?: return null
        val limits = LIMITS.find(text)?.groupValues
        return CardStatementSummary(
            bankName = BANK_NAME,
            cardLast4 = cardLast4(text),
            statementDate = statementDate,
            outstanding = CardStatementText.amount(outstanding),
            minimumDue = MINIMUM_DUE.find(text)?.groupValues?.get(1)?.let(CardStatementText::amount),
            dueDate = DUE_DATE.find(text)?.groupValues?.get(1)?.let { LocalDate.parse(it, SUMMARY_DATE) },
            creditLimit = limits?.get(1)?.let(CardStatementText::amount),
            availableLimit = limits?.get(2)?.let(CardStatementText::amount)
        )
    }

    private fun cardLast4(text: String): String? = CARD.find(text)?.groupValues?.get(1)

    private companion object {
        /** Matches the SMS parser's bank name so rows land on the SMS-tracked card. */
        const val BANK_NAME = "HDFC Bank"

        val ROW = Regex("""^(\d{2}/\d{2}/\d{4})\|\s*(\d{2}:\d{2})\s+(.+?)\s+(\+\s+)?\D?\s?($AMOUNT)(?:\s+\S)?$""")
        val REFERENCE = Regex("""\(Ref#\s*(\d+)\)""")
        val CARD = Regex("""\b\d{4,6}X{6,8}(\d{4})\b""")
        val PERIOD = Regex("""(\d{2} [A-Z][a-z]{2}, \d{4}) - (\d{2} [A-Z][a-z]{2}, \d{4})""")
        val CURRENT_DUES = Regex("""CURRENT DUES\s*\D?($AMOUNT)""")
        val TOTAL_DUE = Regex("""TOTAL AMOUNT DUE\s*\D?($AMOUNT)""")
        val MINIMUM_DUE = Regex("""MINIMUM DUE\s*\D?($AMOUNT)""")
        val DUE_DATE = Regex("""DUE DATE\s*(\d{2} [A-Z][a-z]{2}, \d{4})""")
        // "C50,000 C40,000 C20,000": total, available, cash — whole rupees.
        val LIMITS = Regex("""AVAILABLE CREDIT LIMIT AVAILABLE CASH LIMIT\s*\D?([\d,]+(?:\.\d{2})?)\s+\D?([\d,]+(?:\.\d{2})?)""")

        val ROW_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val SUMMARY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM, yyyy", Locale.ENGLISH)
    }
}
