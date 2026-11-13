package com.moneytracker.app.data.statement

import com.moneytracker.parser.core.ParsedTransaction
import com.moneytracker.parser.core.TransactionType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** SBI rows carry a date only; the import matches them as date-only. */
internal const val SBI_PDF_SENDER = "SBI PDF"

/**
 * State Bank of India savings-account e-statement. After text extraction the
 * transactions come out one per line (figures illustrative):
 * ```
 * TRANSACTION DETAILS
 * SAVING ACCOUNT
 * XXXXXXX1234
 * Date Transaction Reference Ref.No./Chq.No. Credit Debit Balance
 * 03-09-26 UPI/DR/111122223333/Sample Payee /BANK/sample@bank/UPI - 0 40.00 460.00
 * 25-09-26 INTEREST CREDIT - 5.00 0 440.00
 * Your Closing Balance on 30-09-26: 440.00
 * ```
 * Each row ends "reference credit debit balance", so the direction is read from
 * which of the two amounts is non-zero, and the running balance lets the import
 * check the closing figure (#13). SBI SMS never state a balance, so this
 * statement is where an SBI account's balance comes from.
 */
class SbiAccountPdfParser : PdfStatementParser {

    override fun canHandle(text: String): Boolean {
        val lower = text.lowercase()
        return "sbin0" in lower && "transaction details" in lower && "closing balance" in lower
    }

    override fun parse(text: String): List<ParsedTransaction> {
        val account = ACCOUNT.find(text.substringAfter("TRANSACTION DETAILS", ""))?.groupValues?.get(1)
        return text.lines().mapNotNull { raw ->
            val m = ROW.matchEntire(raw.trim()) ?: return@mapNotNull null
            val (dateRaw, description, reference, creditRaw, debitRaw, balanceRaw) = m.destructured
            val credit = money(creditRaw)
            val debit = money(debitRaw)
            if (credit.signum() == 0 && debit.signum() == 0) return@mapNotNull null
            val date = runCatching { LocalDate.parse(dateRaw, DATE) }.getOrNull() ?: return@mapNotNull null
            val income = credit.signum() > 0
            val balance = money(balanceRaw)
            val parts = description.split('/')
            val isUpi = parts.firstOrNull().equals("UPI", ignoreCase = true) && parts.size > 3
            ParsedTransaction(
                amount = if (income) credit else debit,
                type = if (income) TransactionType.INCOME else TransactionType.EXPENSE,
                merchant = if (isUpi) parts[3].trim().ifBlank { null } else merchantFrom(description),
                reference = if (isUpi) parts[2].trim().takeIf { it.all(Char::isDigit) } else reference.takeIf { it != "-" },
                accountLast4 = account,
                balance = balance,
                // Date and running balance lead the text: the import's duplicate hash is built
                // from it, and the balance makes every row unique.
                smsBody = "${dateRaw} · balance ${balance.toPlainString()}\n$description",
                sender = SBI_PDF_SENDER,
                timestamp = date.atTime(LocalTime.NOON).atZone(IST).toInstant().toEpochMilli(),
                bankName = BANK_NAME
            )
        }
    }

    private fun merchantFrom(description: String): String =
        if (description.startsWith("INTEREST", ignoreCase = true)) "Interest" else description.take(MAX_MERCHANT).trim()

    private fun money(raw: String) = BigDecimal(raw.replace(",", ""))

    private companion object {
        /** Matches the SMS parser's bank name so rows land on the SMS-tracked account. */
        const val BANK_NAME = "State Bank of India"
        const val MAX_MERCHANT = 40
        val IST: ZoneId = ZoneId.of("Asia/Kolkata")
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yy")
        const val AMOUNT = """[\d,]+(?:\.\d+)?"""
        val ACCOUNT = Regex("""X{4,}(\d{4})""")
        // "03-09-26 <description> <ref> <credit> <debit> <balance>"
        val ROW = Regex("""^(\d{2}-\d{2}-\d{2})\s+(.+?)\s+(\S+)\s+($AMOUNT)\s+($AMOUNT)\s+($AMOUNT)$""")
    }
}
