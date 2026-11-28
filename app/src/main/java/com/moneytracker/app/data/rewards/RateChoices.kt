package com.moneytracker.app.data.rewards

import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import java.math.BigDecimal

/**
 * Purchases whose SMS can not say which of two rates they earned, so the person switches by hand. The choice is
 * saved as that purchase's own rate.
 */
object RateChoices {

    enum class Kind { AMAZON, TATA_PARTNER }

    data class Choice(val kind: Kind, val rates: List<BigDecimal>)

    private val AMAZON = Choice(Kind.AMAZON, listOf(BigDecimal("5"), BigDecimal("2")))
    private val TATA = Choice(Kind.TATA_PARTNER, listOf(BigDecimal("2"), BigDecimal("7")))
    private val CLOSE = BigDecimal("0.25")

    /** The two rates to switch between for [tx] on [program]'s card, or null when there is nothing to choose. */
    fun forPurchase(program: CardRewardProgram, tx: TransactionEntity): Choice? {
        if (tx.transactionType != TransactionType.CREDIT) return null
        val text = "${tx.merchantName} ${tx.category} ${tx.description.orEmpty()}".lowercase()
        return when (program.name) {
            // Shopping pays 5%; vouchers, gift cards and recharges bought on Amazon pay 2%.
            "Amazon Pay ICICI" -> AMAZON.takeIf { "amazon" in text }
            // A partner pays 2% on the card; bought through the Tata Neu app, the app adds 5%.
            "Tata Neu Plus" -> TATA.takeIf {
                val day = tx.dateTime.toLocalDate()
                program.rules.any { (it.label == "Tata Neu partners" || it.label == "EMI at Titan") && it.matches(text, day) }
            }
            else -> null
        }
    }

    /** What the Tata Neu app credits on top of the card for a purchase made in it; no card statement prints it. */
    val NEU_APP_PERCENT = BigDecimal("5")

    /**
     * True when [rate] is one of the Tata switch's choices for [tx]. A statement prints 1% on the row and the partner's
     * extra 1% in a bonus total that is counted for the whole card, so once it lists the purchase the card's part is
     * the statement's: replacing it with 2% or 7% would count the bonus twice. The choice then only says whether the
     * Neu app added its part.
     */
    fun statementKeepsCardPart(program: CardRewardProgram, tx: TransactionEntity, rate: BigDecimal): Boolean =
        forPurchase(program, tx)?.let { c -> c.kind == Kind.TATA_PARTNER && c.rates.any { it.compareTo(rate) == 0 } } == true

    /** True when the switch's [rate] means the purchase was made in the Neu app. */
    fun includesNeuApp(rate: BigDecimal): Boolean = rate.compareTo(TATA.rates.last()) == 0

    /** The choice a reward at [effectivePercent] stands for (a statement's figure is rounded to whole points). */
    fun selected(choice: Choice, effectivePercent: BigDecimal): BigDecimal? =
        choice.rates.firstOrNull { (it - effectivePercent).abs() <= CLOSE }
}
