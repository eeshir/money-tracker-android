package com.pennywiseai.tracker.data.statement

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType
import com.pennywiseai.tracker.data.statement.CardStatementText.AMOUNT
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

internal const val HSBC_CC_PDF_SENDER = "HSBC CC PDF"

/**
 * HSBC India credit card statement. The summary's labels and figures come out
 * on separate lines (figures illustrative):
 * ```
 * 09 NOV 2026 180.52                          ← due date, minimum due
 * 26 SEP 2026  To 25 OCT 2026 2,600.00        ← period, total due
 * 100,000.00 20,000.00                        ← credit limit, cash limit
 *  Available Credit Limit           97,400.00
 * 43xx xxxx xxxx 1234 CARDHOLDER NAME
 * 25SEP SOME MERCHANT          CITY          IN 267.00
 * 01OCT BBPS PMT …                 1,000.00 CR
 * 25OCT NET OUTSTANDING BALANCE 2,600.00
 * ```
 * Rows print day and month only; the year comes from the statement period.
 */
class HsbcCreditCardPdfParser : CardStatementParser {

    override fun canHandle(text: String): Boolean {
        val lower = text.lowercase()
        return "hsbc" in lower && "credit card" in lower && "net outstanding balance" in lower
    }

    override fun parse(text: String): List<ParsedTransaction> {
        val periodEnd = period(text)?.second ?: return emptyList()
        var card = cardLast4(text)
        return text.lines().mapNotNull { raw ->
            val line = raw.trim()
            CARD_HEADER.find(line)?.let { card = it.groupValues[1]; return@mapNotNull null }
            val m = ROW.matchEntire(line) ?: return@mapNotNull null
            val (dayMonth, descriptionRaw, amountRaw, cr) = m.destructured
            val description = CardStatementText.cleanDescription(descriptionRaw)
            if (description.startsWith("NET OUTSTANDING BALANCE")) return@mapNotNull null
            val date = dateIn(MonthDay.parse(dayMonth, ROW_DATE), periodEnd)
            val amount = CardStatementText.amount(amountRaw)
            ParsedTransaction(
                amount = amount,
                type = if (cr.isNotEmpty()) TransactionType.INCOME else TransactionType.CREDIT,
                merchant = description,
                reference = null,
                accountLast4 = card,
                balance = null,
                smsBody = "$dayMonth ${date.year} · card $card · $description · ${amount.toPlainString()}",
                sender = HSBC_CC_PDF_SENDER,
                timestamp = CardStatementText.millisAtNoon(date),
                bankName = BANK_NAME,
                isFromCard = true
            )
        }
    }

    override fun summary(text: String): CardStatementSummary? {
        val (_, end) = period(text) ?: return null
        val outstanding = NET_OUTSTANDING.find(text)?.groupValues?.get(1) ?: return null
        val due = DUE.find(text)?.groupValues
        return CardStatementSummary(
            bankName = BANK_NAME,
            cardLast4 = cardLast4(text),
            statementDate = end,
            outstanding = CardStatementText.amount(outstanding),
            minimumDue = due?.get(2)?.let(CardStatementText::amount),
            dueDate = due?.get(1)?.let { LocalDate.parse(it, SUMMARY_DATE) },
            creditLimit = LIMITS.find(text)?.groupValues?.get(1)?.let(CardStatementText::amount),
            availableLimit = AVAILABLE.find(text)?.groupValues?.get(1)?.let(CardStatementText::amount)
        )
    }

    private fun period(text: String): Pair<LocalDate, LocalDate>? =
        PERIOD.find(text)?.let {
            LocalDate.parse(it.groupValues[1], SUMMARY_DATE) to LocalDate.parse(it.groupValues[2], SUMMARY_DATE)
        }

    private fun cardLast4(text: String): String? = CARD_HEADER.find(text)?.groupValues?.get(1)

    /** The row's day in the period ending [end]: a month after [end]'s belongs to the year before. */
    private fun dateIn(day: MonthDay, end: LocalDate): LocalDate =
        day.atYear(if (day.monthValue > end.monthValue) end.year - 1 else end.year)

    private companion object {
        const val BANK_NAME = "HSBC Bank"

        val CARD_HEADER = Regex("""(?m)^\s*\d{2}xx xxxx xxxx (\d{4})\b""")
        val ROW = Regex("""^(\d{2}[A-Z]{3}) (.+?)\s+($AMOUNT)( CR)?$""")
        val PERIOD = Regex("""(\d{2} [A-Z]{3} \d{4})\s+To\s+(\d{2} [A-Z]{3} \d{4})""")
        val NET_OUTSTANDING = Regex("""NET OUTSTANDING BALANCE\s+($AMOUNT)""")
        val DUE = Regex("""(?m)^(\d{2} [A-Z]{3} \d{4}) ($AMOUNT)\s*$""")
        // The line after the period: "credit limit cash limit".
        val LIMITS = Regex("""To \d{2} [A-Z]{3} \d{4}.*\R\s*($AMOUNT) $AMOUNT""")
        val AVAILABLE = Regex("""Available Credit Limit\s+($AMOUNT)""")

        val ROW_DATE: DateTimeFormatter = DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("ddMMM").toFormatter(Locale.ENGLISH)
        val SUMMARY_DATE: DateTimeFormatter = DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("dd MMM yyyy").toFormatter(Locale.ENGLISH)
    }
}
