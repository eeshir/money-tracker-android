package com.moneytracker.app.data.statement

import com.moneytracker.parser.core.ParsedTransaction
import com.moneytracker.parser.core.TransactionType
import com.moneytracker.app.data.statement.CardStatementText.AMOUNT
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** HDFC card rows carry a date and a time that is often 00:00; the import matches them as date-only. */
internal const val HDFC_CC_PDF_SENDER = "HDFC CC PDF"

/**
 * HDFC Bank credit card statement. After text extraction (figures illustrative):
 * ```
 * TOTAL AMOUNT DUE
 * C9,000.00
 * MINIMUM DUE
 * C880.00
 * DUE DATE
 * 07 Dec, 2026
 * 17/09/2026| 00:00 SOME MERCHANT (Ref# 0123)  C 32.58 l
 * 18/09/2026| 20:11 CC PAYMENT … (Ref# 0456) +  C 3,000.00 l
 * 13/01/2026| 10:00 SOME PARTNER + 25  C 1,000.00 l        ← Tata Neu: "+ 25" is the row's base NeuCoins
 * CURRENT DUES
 * C880.00                                                  ← the minimum due again, NOT the amount to pay
 * AVAILABLE CREDIT LIMIT AVAILABLE CASH LIMIT
 * C50,000 C41,000 C20,000
 * 4632XXXXXXXX1234 …  18 Sep, 2026 - 17 Oct, 2026
 * ```
 * The rupee sign comes out as a stray glyph ("C"), so amounts are read after any one non-digit. A "+" before the
 * amount marks a payment or refund; a "+ 25" before it, on a Tata Neu statement, is NeuCoins earned on that row.
 * "CURRENT DUES" equals the minimum due on every statement seen, so the amount to pay is "TOTAL AMOUNT DUE".
 */
class HdfcCreditCardPdfParser : CardStatementParser {

    override fun canHandle(text: String): Boolean {
        val lower = text.lowercase()
        return "hdfc bank credit cards" in lower && "credit card statement" in lower
    }

    /** One statement row as printed, before it becomes a transaction. */
    private class Row(
        val dateRaw: String, val time: String, val date: LocalDate, val rawDescription: String,
        val description: String, val credit: Boolean, val amount: BigDecimal,
        /** The Tata Neu "Base NeuCoins" column: positive earned, negative taken back, null when the row has none. */
        val coins: Int?
    )

    private fun rows(text: String): List<Row> {
        val hasCoinsColumn = COINS_HEADER in text
        return text.lines().mapNotNull { line ->
            val m = ROW.matchEntire(line.trim()) ?: return@mapNotNull null
            val (dateRaw, time, descriptionRaw, plus, amountRaw) = m.destructured
            var description = descriptionRaw
            var coins: Int? = null
            // On a Tata Neu statement "+ 25" before the amount is NeuCoins, not a credit: a credit's "+" sits right
            // before the amount (captured as [plus]).
            if (hasCoinsColumn && plus.isBlank()) {
                COINS_TAIL.find(descriptionRaw)?.let {
                    coins = it.groupValues[2].toInt() * if (it.groupValues[1] == "-") -1 else 1
                    description = descriptionRaw.substring(0, it.range.first)
                }
            }
            Row(
                dateRaw, time, LocalDate.parse(dateRaw, ROW_DATE), descriptionRaw,
                CardStatementText.cleanDescription(description), plus.isNotBlank(),
                CardStatementText.amount(amountRaw), coins
            )
        }
    }

    override fun parse(text: String): List<ParsedTransaction> {
        val card = cardLast4(text)
        return rows(text).map { row ->
            ParsedTransaction(
                amount = row.amount,
                type = if (row.credit) TransactionType.INCOME else TransactionType.CREDIT,
                merchant = row.description,
                reference = REFERENCE.find(row.rawDescription)?.groupValues?.get(1),
                accountLast4 = card,
                balance = null,
                smsBody = "${row.dateRaw} ${row.time} · card $card · ${row.description} · ${row.amount.toPlainString()}",
                sender = HDFC_CC_PDF_SENDER,
                timestamp = CardStatementText.millisAtNoon(row.date),
                bankName = BANK_NAME,
                isFromCard = true
            )
        }
    }

    /** Tata Neu statements print each row's base NeuCoins; other HDFC cards print none, so they return nothing. */
    override fun rowRewardDetails(text: String): List<StatementRowReward> {
        if (COINS_HEADER !in text) return emptyList()
        return rows(text).map { row ->
            StatementRowReward(
                date = row.date, amount = row.amount,
                points = BigDecimal(row.coins ?: 0), credit = row.credit, description = row.description
            )
        }
    }

    override fun rowRewards(text: String): BigDecimal? =
        rowRewardDetails(text).takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO) { a, b -> a + b.points }

    override fun summary(text: String): CardStatementSummary? {
        val statementDate = PERIOD.find(text)?.groupValues?.get(2)?.let { LocalDate.parse(it, SUMMARY_DATE) } ?: return null
        // "CURRENT DUES" is the minimum due on every HDFC statement seen, so the total comes first.
        val outstanding = (TOTAL_DUE.find(text) ?: CURRENT_DUES.find(text))?.groupValues?.get(1) ?: return null
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
        const val COINS_HEADER = "Base NeuCoins"

        val ROW = Regex("""^(\d{2}/\d{2}/\d{4})\|\s*(\d{2}:\d{2})\s+(.+?)\s+(\+\s+)?\D?\s?($AMOUNT)(?:\s+\S)?$""")
        val COINS_TAIL = Regex("""\s+([+-])\s+(\d+)$""")
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
