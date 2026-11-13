package com.pennywiseai.tracker.domain.loan

import com.pennywiseai.tracker.data.database.entity.LoanDirection
import com.pennywiseai.tracker.data.database.entity.LoanEntity
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import com.pennywiseai.tracker.utils.CurrencyFormatter
import java.math.BigDecimal
import java.time.LocalDate

/** One bill that makes up what a person owes you, at their share of it. */
data class SplitLine(val title: String, val date: LocalDate, val amount: BigDecimal)

/** Everything one person owes you in one currency, compiled across all your split bills and loans. */
data class PersonSplitTotal(
    val person: String,
    val currency: String,
    /** Still unpaid: loans' remaining amounts, so repayments are already taken off. */
    val total: BigDecimal,
    val lines: List<SplitLine>,
    /** The open loans this total is made of, for settling or deleting them together. */
    val loanIds: List<Long> = emptyList()
) {
    val key: String get() = "$person|$currency"
}

object SplitSummary {
    /**
     * Compiles open "you lent" loans with their bills into one total per person and
     * currency. A loan with no readable bills still counts toward the total.
     */
    fun compile(loansWithBills: List<Pair<LoanEntity, List<TransactionEntity>>>): List<PersonSplitTotal> =
        loansWithBills
            .filter { (loan, _) -> loan.direction == LoanDirection.LENT && loan.remainingAmount.signum() > 0 }
            .groupBy { (loan, _) -> loan.personName to loan.currency.uppercase() }
            .map { (who, entries) ->
                val lines = entries.flatMap { (_, bills) -> bills }
                    .filter { it.transactionType != TransactionType.INCOME }
                    .distinctBy { it.id }
                    .map { SplitLine(it.merchantName, it.dateTime.toLocalDate(), it.loanContribution ?: it.amount) }
                    .sortedBy { it.date }
                PersonSplitTotal(
                    person = who.first,
                    currency = who.second,
                    total = entries.fold(BigDecimal.ZERO) { acc, (loan, _) -> acc + loan.remainingAmount },
                    lines = lines,
                    loanIds = entries.map { (loan, _) -> loan.id }
                )
            }
            .sortedWith(compareBy<PersonSplitTotal>({ it.person.lowercase() }, { it.currency }))

    /** One message for the whole sum: the bills, then a single total (and UPI link if any). */
    fun requestText(owed: PersonSplitTotal, payUri: String?, upiId: String?): String = buildString {
        append("Hi ").append(owed.person).append(", here is what is pending from our splits:\n")
        owed.lines.forEach {
            append("- ").append(it.title).append(" (").append(it.date).append("): ")
                .append(CurrencyFormatter.formatCurrency(it.amount, owed.currency)).append('\n')
        }
        append("Total: ").append(CurrencyFormatter.formatCurrency(owed.total, owed.currency))
        if (upiId != null) append("\nPay to ").append(upiId)
        if (payUri != null) append('\n').append(payUri)
    }
}
