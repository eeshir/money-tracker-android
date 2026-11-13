package com.moneytracker.app.data.statement

import com.moneytracker.parser.core.ParsedTransaction
import com.moneytracker.parser.core.TransactionType
import com.moneytracker.app.data.statement.CardStatementText.AMOUNT
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val ICICI_CC_PDF_SENDER = "ICICI CC PDF"

/**
 * ICICI Bank credit card statement (Amazon Pay, Coral, …). After text
 * extraction (figures illustrative):
 * ```
 * 4315XXXXXXXX1234
 * 10/09/2026 14145113654 SOME MERCHANT BANGALORE
 * IN
 * 14 283.00
 * 04/10/2026 14289172816 PAYMENT RECEIVED, THANK YOU 0 1,000.00 CR
 * Credit Limit (Including cash) Available Credit (Including cash) Cash Limit Available Cash
 * `1,00,000.00 `99,217.00 `10,000.00 `10,000.00
 * Statement period : September 6, 2026 to October 5, 2026
 * Previous Balance Purchases / Charges Cash Advances Payments / Credits
 * `0.00 `1,783.00 `0.00 `1,000.00
 * ```
 * A row starts "date serial" and wraps until its "reward points amount [CR]"
 * tail. Rows follow the card number they're listed under (add-on cards get
 * their own block). The rupee sign comes out as "`".
 */
class IciciCreditCardPdfParser : CardStatementParser {

    override fun canHandle(text: String): Boolean {
        val lower = text.lowercase()
        return "icici bank credit card" in lower && "credit card statement" in lower
    }

    override fun parse(text: String): List<ParsedTransaction> = scan(text, null)

    /** Reads the rows; [points] collects each row's reward column (taken off for a credit). */
    private fun scan(text: String, rewards: MutableList<StatementRowReward>?): List<ParsedTransaction> {
        val rows = mutableListOf<ParsedTransaction>()
        var card = cardLast4(text)
        var open: Pair<MatchResult, StringBuilder>? = null

        fun close(tail: MatchResult) {
            val (start, details) = open ?: return
            open = null
            val (dateRaw, serial) = start.destructured
            val date = LocalDate.parse(dateRaw, ROW_DATE)
            val description = details.toString().replace(Regex("""\s+"""), " ").trim()
            val amount = CardStatementText.amount(tail.groupValues[1])
            val credit = tail.groupValues[2].isNotEmpty()
            // A credit prints its reward already negative; a purchase positive. Either way a credit takes points off.
            val printed = BigDecimal(FIRST_INT.find(tail.value)!!.value).abs()
            rewards?.add(StatementRowReward(date, amount, if (credit) printed.negate() else printed, credit, description))
            rows += ParsedTransaction(
                amount = amount,
                type = if (tail.groupValues[2].isNotEmpty()) TransactionType.INCOME else TransactionType.CREDIT,
                merchant = description,
                reference = serial,
                accountLast4 = card,
                balance = null,
                smsBody = "$dateRaw · $serial · card $card · $description · ${amount.toPlainString()}",
                sender = ICICI_CC_PDF_SENDER,
                timestamp = CardStatementText.millisAtNoon(date),
                bankName = BANK_NAME,
                isFromCard = true
            )
        }

        for (raw in text.lines()) {
            val line = raw.trim()
            CARD_HEADER.matchEntire(line)?.let { card = it.groupValues[1]; open = null; continue }
            ROW_START.find(line)?.let { start ->
                val rest = line.substring(start.range.last + 1)
                val tail = TAIL.find(rest)
                open = start to StringBuilder(if (tail != null) rest.substring(0, tail.range.first) else rest)
                if (tail != null) close(tail)
                continue
            }
            val pending = open ?: continue
            val tail = TAIL.find(line)
            if (tail != null) {
                pending.second.append(' ').append(line.substring(0, tail.range.first))
                close(tail)
            } else {
                pending.second.append(' ').append(line)
            }
        }
        return rows
    }

    override fun summary(text: String): CardStatementSummary? {
        val statementDate = PERIOD.find(text)?.groupValues?.get(2)?.let { LocalDate.parse(it, LONG_DATE) } ?: return null
        // Previous balance + purchases + cash advances − payments: the statement balance.
        val totals = TOTALS.find(text)?.groupValues?.drop(1)?.map(CardStatementText::amount) ?: return null
        val outstanding = totals[0] + totals[1] + totals[2] - totals[3]
        val limits = LIMITS.find(text)?.groupValues
        // The due date is printed beside the statement date, unlabelled: the later of the two.
        val dueDate = STANDALONE_DATE.findAll(text)
            .map { LocalDate.parse(it.groupValues[1], LONG_DATE) }
            .filter { it.isAfter(statementDate) }
            .minOrNull()
        return CardStatementSummary(
            bankName = BANK_NAME,
            cardLast4 = cardLast4(text),
            statementDate = statementDate,
            outstanding = outstanding.max(BigDecimal.ZERO),
            dueDate = dueDate,
            creditLimit = limits?.get(1)?.let(CardStatementText::amount),
            availableLimit = limits?.get(2)?.let(CardStatementText::amount)
        )
    }

    override fun rowRewards(text: String): BigDecimal? {
        val rows = rowRewardDetails(text)
        return if (rows.isEmpty()) null else rows.fold(BigDecimal.ZERO) { a, b -> a + b.points }
    }

    override fun rowRewardDetails(text: String): List<StatementRowReward> =
        mutableListOf<StatementRowReward>().also { scan(text, it) }

    private fun cardLast4(text: String): String? = CARD_HEADER.find(text)?.groupValues?.get(1)

    private companion object {
        const val BANK_NAME = "ICICI Bank"

        val CARD_HEADER = Regex("""(?m)^\s*\d{4}X{8}(\d{4})\s*$""")
        val ROW_START = Regex("""^(\d{2}/\d{2}/\d{4}) (\d{8,})\b""")
        // "<reward points> <amount> [CR]" at the end of a row.
        val FIRST_INT = Regex("""-?\d+""")
        val TAIL = Regex("""(?:^|\s)-?\d+\s+($AMOUNT)(\s+CR)?\s*$""")
        val PERIOD = Regex("""Statement period\s*:\s*([A-Z][a-z]+ \d{1,2}, \d{4}) to ([A-Z][a-z]+ \d{1,2}, \d{4})""")
        val TOTALS = Regex("""Previous Balance Purchases / Charges Cash Advances Payments / Credits\s*\D?($AMOUNT)\s+\D?($AMOUNT)\s+\D?($AMOUNT)\s+\D?($AMOUNT)""")
        val LIMITS = Regex("""Available Cash\s*\D?($AMOUNT)\s+\D?($AMOUNT)""")
        val STANDALONE_DATE = Regex("""(?m)^\s*([A-Z][a-z]+ \d{1,2}, \d{4})\s*$""")

        val ROW_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
    }
}
