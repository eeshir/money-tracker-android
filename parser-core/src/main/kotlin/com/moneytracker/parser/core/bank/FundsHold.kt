package com.moneytracker.parser.core.bank

import java.math.BigDecimal

/**
 * A bank holding part of an account for a UPI mandate (an IPO application, an
 * autopay) and releasing it later: "your account has been blocked by an amount
 * of INR 15000.00, against the UPI one-time mandate". The money isn't spent, but
 * the bank's *available* balance — the figure its balance SMS state — drops by the
 * amount while it is held and returns when it is released. Following both keeps
 * the account on that same footing. The message names no account.
 */
object FundsHold {

    private val HOLD = Regex(
        """your account has been (blocked|unblocked) by an amount of\s*(?:INR|Rs\.?)\s*([\d,]+(?:\.\d+)?)\s*,?\s*against the UPI""",
        RegexOption.IGNORE_CASE
    )

    /** India Standard Time's offset from UTC, and a day, in milliseconds (for counting calendar days). */
    const val IST_OFFSET_MILLIS = 19_800_000L
    const val DAY_MILLIS = 86_400_000L

    /** True when [message] is a hold or a release. */
    fun isHold(message: String): Boolean = HOLD.containsMatchIn(message)

    /** The hold's direction and amount, or null if [message] is not one. */
    fun parse(message: String): Hold? {
        val match = HOLD.find(message) ?: return null
        val amount = match.groupValues[2].replace(",", "").toBigDecimalOrNull() ?: return null
        return Hold(blocked = match.groupValues[1].equals("blocked", ignoreCase = true), amount = amount)
    }

    data class Hold(val blocked: Boolean, val amount: BigDecimal)
}
