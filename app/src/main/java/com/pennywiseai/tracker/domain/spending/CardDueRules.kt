package com.pennywiseai.tracker.domain.spending

import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A card payment to show on Home, in the card's own currency. */
data class CardDueItem(
    val key: String,
    val cardLabel: String,
    val currency: String,
    val dueDate: LocalDate,
    /** Still to pay: the statement total less what has been paid since. */
    val remaining: BigDecimal,
    val minimumDue: BigDecimal?,
    /** At least the minimum has been paid, the rest is still open. */
    val minimumPaid: Boolean,
    val daysLeft: Long
) {
    val isOverdue: Boolean get() = daysLeft < 0
}

object CardDueRules {
    /** An unpaid bill stays on Home this long after its due date. */
    const val OVERDUE_GRACE_DAYS = 30L

    /** Smaller than this left over is rounding, not a bill. */
    private val SETTLED = BigDecimal("1")

    /**
     * The item to show for a statement due on [dueDate], or null when it is
     * settled (paid, marked paid, or the leftover is negligible) or stale.
     */
    fun item(
        key: String,
        cardLabel: String,
        currency: String,
        dueDate: LocalDate,
        totalDue: BigDecimal,
        minimumDue: BigDecimal?,
        paidSince: BigDecimal,
        markedPaid: Boolean,
        today: LocalDate
    ): CardDueItem? {
        if (markedPaid) return null
        val remaining = (totalDue - paidSince).max(BigDecimal.ZERO)
        if (remaining < SETTLED) return null
        val daysLeft = ChronoUnit.DAYS.between(today, dueDate)
        if (daysLeft < -OVERDUE_GRACE_DAYS) return null
        return CardDueItem(
            key = key, cardLabel = cardLabel, currency = currency, dueDate = dueDate,
            remaining = remaining, minimumDue = minimumDue,
            minimumPaid = minimumDue != null && paidSince >= minimumDue && paidSince.signum() > 0,
            daysLeft = daysLeft
        )
    }
}
