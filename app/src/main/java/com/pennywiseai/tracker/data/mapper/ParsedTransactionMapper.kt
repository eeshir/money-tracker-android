package com.pennywiseai.tracker.data.mapper

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.tracker.core.Constants
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.shared.domain.mapping.SharedCategoryMapping
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Maps ParsedTransaction from parser-core to TransactionEntity
 */
fun ParsedTransaction.toEntity(): TransactionEntity {
    val dateTime = LocalDateTime.ofInstant(
        Instant.ofEpochMilli(timestamp),
        ZoneId.systemDefault()
    )

    // Normalize merchant name to proper case
    val normalizedMerchant = merchant?.let { normalizeMerchantName(it) }

    // Map TransactionType from parser-core to database entity
    val entityType = when (type) {
        com.pennywiseai.parser.core.TransactionType.INCOME -> TransactionType.INCOME
        com.pennywiseai.parser.core.TransactionType.EXPENSE -> TransactionType.EXPENSE
        com.pennywiseai.parser.core.TransactionType.CREDIT -> TransactionType.CREDIT
        com.pennywiseai.parser.core.TransactionType.TRANSFER -> TransactionType.TRANSFER
        com.pennywiseai.parser.core.TransactionType.INVESTMENT -> TransactionType.INVESTMENT
        com.pennywiseai.parser.core.TransactionType.BALANCE_UPDATE -> TransactionType.EXPENSE
    }

    // The merchant as the SMS wrote it; if that finds no category, the brand it belongs to
    // ("Blink Commerce Pvt Ltd" → Blinkit → Groceries).
    var category = determineCategory(merchant, entityType).let { byRawName ->
        if (byRawName == BuiltinCategoryNames.current("Others")) determineCategory(normalizedMerchant, entityType) else byRawName
    }

    // Detect Credit Card Bill Payments to prevent double-counting of expenses.
    // Buying on a credit card is recorded as an expense (e.g. Swiggy/Food), so the
    // bill payment must not count again: it's excluded from analytics. Its type is
    // kept — the bank-side debit still lowers the bank balance and the card-side
    // "payment received" still lowers what the card owes. Retyping it TRANSFER
    // (with no from/to accounts) froze both balances.
    val isCcBillPayment = category == "Credit Card Payment" ||
        isCreditCardPayment(normalizedMerchant, smsBody, entityType)

    // A bank holding funds for a UPI mandate (an IPO application) and releasing them later is
    // not spending or income: it only moves the available balance.
    val excludedFromAnalytics = if (isCcBillPayment) {
        category = "Credit Card Payment"
        true
    } else {
        com.pennywiseai.parser.core.bank.FundsHold.isHold(smsBody)
    }

    return TransactionEntity(
        id = 0, // Auto-generated
        amount = amount,
        merchantName = when {
            // The SMS names only a transfer code ("Inft*fj49"); say what it is.
            isIciciCardBillPay(smsBody) -> "ICICI Credit Card Payment"
            isCcBillPayment && (normalizedMerchant == null || normalizedMerchant == "Unknown Merchant") -> "Credit Card Payment"
            else -> normalizedMerchant ?: "Unknown Merchant"
        },
        category = category,
        transactionType = entityType,
        dateTime = dateTime,
        description = null,
        smsBody = smsBody,
        bankName = bankName,
        smsSender = sender,
        accountNumber = accountLast4,
        balanceAfter = balance,
        transactionHash = transactionHash?.takeIf { it.isNotBlank() } ?: generateTransactionId(),
        isRecurring = false, // Will be determined later
        excludedFromAnalytics = excludedFromAnalytics,
        createdAt = LocalDateTime.now(),
        updatedAt = LocalDateTime.now(),
        currency = currency,
        fromAccount = fromAccount,
        toAccount = toAccount,
        reference = reference
    )
}

/**
 * ICICI net-banking bill pay to the person's own ICICI card: the debit SMS ends
 * "…InfoBIL*INFT*FJ49." with only a transfer code, not a payee. On every one seen
 * the amount was exactly a card's balance being paid off.
 */
internal fun isIciciCardBillPay(smsBody: String?): Boolean =
    smsBody?.contains("BIL*INFT*", ignoreCase = true) == true

/** The CRED app by its name as a word: not "Credit", "Cashback Credit" or "PaisabazaarCred". */
private val CRED_APP_NAME = Regex("""\bcred\b""")

/**
 * Whether a transaction is a credit-card bill payment — money moving between the person's own
 * accounts, kept out of spending and income (the card's own spends are what count).
 * Errs towards counting: a real spend hidden here is worse than a payment shown twice.
 */
internal fun isCreditCardPayment(merchant: String?, smsBody: String?, type: TransactionType): Boolean {
    val m = merchant?.lowercase().orEmpty()
    val body = smsBody?.lowercase().orEmpty()
    if (CRED_APP_NAME.containsMatchIn(m) || m.contains("credit card payment") || m.contains("cc payment")) {
        return true
    }
    // The bank-side debit, and a card statement's own "PAYMENT RECEIVED, THANK YOU" row.
    if (isIciciCardBillPay(smsBody) || (type == TransactionType.INCOME && m.contains("payment received"))) return true
    val mentionsCard = body.contains("credit card") || body.contains("card ending") || body.contains("cc bill")
    // BillDesk pays any biller (electricity, insurance…): a card payment only when the SMS says card.
    if (m.contains("billdesk") && mentionsCard) return true
    val mentionsPayment = body.contains("payment of") || body.contains("payment towards") ||
        body.contains("payment received") || body.contains("auto debit") || body.contains("autodebit") ||
        body.contains("bill payment") || body.contains("towards your card")
    return mentionsCard && mentionsPayment
}

/** Account-resolution facts stay separate from rule-selected financial values. */
internal data class DeferredBalanceInput(
    val account: ParsedTransaction,
    val type: com.pennywiseai.parser.core.TransactionType,
    val amount: java.math.BigDecimal
) {
    fun forSavedTransaction(resolvedAccount: ParsedTransaction, saved: TransactionEntity): ParsedTransaction? =
        resolvedAccount.forDeferredBalance(saved)?.copy(type = type, amount = amount)
}

/** Capture rule-selected identity before a balance update leaves the save lock. */
internal fun ParsedTransaction.atEnqueuedAccount(
    resolvedInput: ParsedTransaction,
    saved: TransactionEntity
): DeferredBalanceInput? {
    if (saved.currency != currency) return null
    val account = if (isFromCard) {
        // A transaction rule does not change the issuing bank or the card binding.
        this
    } else {
        val selectedBank = saved.bankName ?: return null
        val sameAccount = selectedBank == resolvedInput.bankName &&
            saved.accountNumber == resolvedInput.accountLast4
        copy(
            bankName = selectedBank,
            // Retain the original mask and type for later confirmed alias changes.
            accountLast4 = if (sameAccount) accountLast4 else saved.accountNumber,
            balance = if (sameAccount) balance else null
        )
    }
    val selectedType = if (saved.transactionType == type.toEntityType()) type
        else com.pennywiseai.parser.core.TransactionType.valueOf(saved.transactionType.name)
    return DeferredBalanceInput(account, selectedType, saved.amount)
}

/** Route a queued bank balance to the transaction's current account after edits or merges. */
internal fun ParsedTransaction.forDeferredBalance(saved: TransactionEntity): ParsedTransaction? {
    if (saved.isDeleted || saved.currency != currency) return null
    // A card suffix identifies the card itself; its account binding is resolved separately.
    if (isFromCard) return this
    // Absolute SMS balances belong to the source account. The caller resolves
    // confirmed aliases first; a move anywhere else must not overwrite its target.
    if (bankName != saved.bankName || (accountLast4 != null && accountLast4 != saved.accountNumber)) return null
    return this
}

/**
 * Normalizes merchant name to consistent format.
 * Canonicalizes known brands (Zomato/Eternal, Swiggy, Swiggy Instamart, etc.)
 * and converts all-caps to proper case while preserving mixed case.
 */
fun canonicalizeMerchantName(name: String): String {
    val trimmed = name.trim()
    val lower = trimmed.lowercase()

    return when {
        // Swiggy Instamart -> check before general Swiggy
        lower.contains("instamart") -> "Swiggy Instamart"
        // General Swiggy (Food)
        lower.contains("swiggy") || lower.contains("bundl tech") || lower.contains("bundltech") -> "Swiggy"
        // Zomato / Eternal Limited
        lower.contains("zomato") || lower.contains("eternal limited") || lower.contains("eternal ltd") || lower == "eternal" -> "Zomato"
        // Blinkit
        // "Blink Commerce Pvt Ltd" / "IAP BLINK CO…" are the company behind Blinkit.
        lower.contains("blinkit") || lower.contains("grofers") || lower.contains("blink co") -> "Blinkit"
        // Zepto
        lower.contains("zepto") || lower.contains("kiranakart") -> "Zepto"
        // BigBasket
        lower.contains("bigbasket") || lower.contains("bbdaily") || lower.contains("bb daily") -> "BigBasket"
        // "Amazon Pay In E", "Amazon Pay Indi", "Amazon Pay Transaction"… are all Amazon. Prime
        // and AWS are separate services and keep their own names.
        lower.contains("amazon") && !lower.contains("prime") && !lower.contains("aws") &&
            !lower.contains("web services") -> "Amazon"
        lower.contains("myntra") -> "Myntra"
        else -> {
            if (trimmed == trimmed.uppercase()) {
                trimmed.lowercase().split(" ").joinToString(" ") { word ->
                    if (word.isEmpty()) word else word.substring(0, 1).uppercase() + word.substring(1)
                }
            } else {
                trimmed
            }
        }
    }
}

private fun normalizeMerchantName(name: String): String = canonicalizeMerchantName(name)

/**
 * Determines the category based on merchant name and transaction type.
 * Delegates to SharedCategoryMapping (single source of truth).
 */
internal fun determineCategory(merchant: String?, type: TransactionType): String {
    val merchantName = merchant ?: return "Others"
    return BuiltinCategoryNames.current(SharedCategoryMapping.determineCategory(merchantName, type.name))
}

/**
 * Extension to map parser-core TransactionType to database entity TransactionType
 */
fun com.pennywiseai.parser.core.TransactionType.toEntityType(): TransactionType {
    return when (this) {
        com.pennywiseai.parser.core.TransactionType.INCOME -> TransactionType.INCOME
        com.pennywiseai.parser.core.TransactionType.EXPENSE -> TransactionType.EXPENSE
        com.pennywiseai.parser.core.TransactionType.CREDIT -> TransactionType.CREDIT
        com.pennywiseai.parser.core.TransactionType.TRANSFER -> TransactionType.TRANSFER
        com.pennywiseai.parser.core.TransactionType.INVESTMENT -> TransactionType.INVESTMENT
        com.pennywiseai.parser.core.TransactionType.BALANCE_UPDATE -> TransactionType.EXPENSE
    }
}