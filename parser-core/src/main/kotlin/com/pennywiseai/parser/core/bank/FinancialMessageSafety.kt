package com.pennywiseai.parser.core.bank

internal object FinancialMessageSafety {

    fun hasExplicitFailure(message: String, additionalPhrases: Collection<String> = emptyList()): Boolean {
        val lower = message.lowercase()
        return GENERIC_FAILURES.any { lower.contains(it) } ||
            additionalPhrases.any { lower.contains(it.lowercase()) }
    }

    fun isSecurityCode(message: String): Boolean {
        val lower = message.lowercase()
        return SECURITY_PHRASES.any { lower.contains(it) }
    }

    fun isOperationalOrPromotionalNotice(message: String): Boolean {
        val lower = message.lowercase()
        return OPERATIONAL_PHRASES.any { lower.contains(it) }
    }

    private val GENERIC_FAILURES = listOf(
        "declined", "decline", "failed", "failure", "not successful",
        "was not completed", "could not be completed", "rejected",
        "cancelled", "canceled", "reversed due to error", "unsuccessful",
        "transaction failed", "payment failed", "could not process", "declined due to"
    )
    private val SECURITY_PHRASES = listOf(
        "your code is", "verification code", "one time password",
        "one-time password", "otp", "secret otp", "login otp", "transaction otp",
        "do not share", "never share", "الرقم السري"
    )
    private val OPERATIONAL_PHRASES = listOf(
        "scheduled maintenance", "service interruption", "service is unavailable",
        "terms and conditions", "learn more", "exclusive offer", "cashback offer",
        "pre-approved", "pre approved", "apply for loan", "credit limit upgrade",
        "bill generated", "statement generated"
    )
}
