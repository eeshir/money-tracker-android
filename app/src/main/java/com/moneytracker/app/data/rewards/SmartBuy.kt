package com.moneytracker.app.data.rewards

import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

/**
 * HDFC SmartBuy on MoneyBack+. Purchases are told apart by what the bank's own SMS says, which a renamed
 * merchant never changes.
 *
 * - [Kind.PARTNER]: a shop opened through SmartBuy ("AT MYNTRA VIA SMARTBUY"). 5% cashback later, and the
 *   card's own 2 CashPoints per Rs 200 as well, as the card's terms (15 May 2026) say and a statement has borne out.
 * - [Kind.VOUCHER_CASHBACK]: a Gyftr voucher on the "GYFTRSM…" route: 5% cashback later,
 *   as bank credits have shown. Vouchers earn no CashPoints.
 * - [Kind.VOUCHER_DISCOUNT]: the "GYFTRVI…" / "SMARTBUY SD" route: the discount came off the price, so no
 *   cashback is expected. Taken from the card owner's research.
 * - [Kind.VOUCHER_UNKNOWN]: any other Gyftr code. No rate is assumed.
 *
 * The digits after the prefix are not used: nothing shows they stand for a brand. Whatever the kind, a bank
 * credit of exactly 5% that arrives for a purchase settles it.
 */
object SmartBuy {
    val RATE_PERCENT: BigDecimal = BigDecimal("5")

    /**
     * Most SmartBuy cashback a calendar month pays on this card. From the card owner's research and card-review
     * sites; HDFC's rules page could not be read to confirm it.
     */
    val MONTHLY_CAP: BigDecimal = BigDecimal("1000")

    const val CAP_LABEL = "SmartBuy cashback"

    /** The longest a bonus credit is looked for: usually 2-5 days, but the bank allows itself up to 90 working days. */
    private val CREDIT_WINDOW: Duration = Duration.ofDays(90)

    private val TOLERANCE = BigDecimal("0.02")

    enum class Kind { PARTNER, VOUCHER_CASHBACK, VOUCHER_DISCOUNT, VOUCHER_UNKNOWN }

    /** Only MoneyBack+ has been checked; other HDFC cards are not assumed to be paid this way. */
    fun appliesTo(program: CardRewardProgram): Boolean =
        "hdfc" in program.bankName.lowercase() && "moneyback" in program.name.lowercase()

    /** How [t] went through SmartBuy, or null when it did not. */
    fun kindOf(t: TransactionEntity): Kind? {
        if (t.transactionType != TransactionType.CREDIT) return null
        val text = "${t.merchantName} ${t.smsBody.orEmpty()}".lowercase()
        return when {
            "gyftrvi" in text || "smartbuy sd" in text -> Kind.VOUCHER_DISCOUNT
            "gyftrsm" in text || ("gyftr" in text && "via smartbuy" in text) -> Kind.VOUCHER_CASHBACK
            "gyftr" in text -> Kind.VOUCHER_UNKNOWN
            // "WWWMYNTR…" is how the bank cuts the Myntra payment made through the SmartBuy portal (the card owner's
            // own purchase); a direct Myntra payment reads "MYNTRA DESIGNS PRIVATE".
            "via smartbuy" in text || "wwwmyntr" in text -> Kind.PARTNER
            else -> null
        }
    }

    fun expectsCashback(kind: Kind): Boolean = kind == Kind.PARTNER || kind == Kind.VOUCHER_CASHBACK

    fun isBonusCredit(t: TransactionEntity): Boolean {
        if (t.transactionType != TransactionType.INCOME) return false
        val text = t.merchantName.lowercase()
        return "smartbuy" in text && "bonus" in text
    }

    /** MoneyBack+ counts a purchase in blocks of this much; what is left over earns no CashPoints. */
    private val BLOCK = BigDecimal(200)

    fun fullBlocks(amount: BigDecimal): BigDecimal = amount.divide(BLOCK, 0, RoundingMode.DOWN).multiply(BLOCK)

    /** The card's own CashPoints on a partner purchase, in rupees: 2 points of 25 paise for each full Rs 200. */
    fun basePoints(amount: BigDecimal, pointValue: BigDecimal): BigDecimal =
        amount.divide(BLOCK, 0, RoundingMode.DOWN).multiply(BigDecimal(2)).multiply(pointValue)
            .setScale(2, RoundingMode.HALF_UP)

    /**
     * Purchase id → the id of the bank credit that paid its 5%. Each credit pays one purchase: one before it,
     * within [CREDIT_WINDOW], whose 5% is the credit's amount. A purchase expected to be paid comes first, then
     * one in [markedByPerson] (set to 5% by hand), then any other Gyftr purchase; the latest of each. A credit
     * that matches nothing still counts as cashback on its own.
     */
    fun pair(transactions: List<TransactionEntity>, markedByPerson: Set<Long> = emptySet()): Map<Long, Long> {
        fun rank(t: TransactionEntity): Int? {
            if (t.transactionType != TransactionType.CREDIT) return null
            val kind = kindOf(t)
            return when {
                kind != null && expectsCashback(kind) -> 0
                t.id in markedByPerson -> 1
                kind != null -> 2
                else -> null
            }
        }
        val purchases = transactions.filter { !it.isDeleted && rank(it) != null }
            .sortedWith(compareBy<TransactionEntity> { rank(it) }.thenByDescending { it.dateTime })
        val paired = LinkedHashMap<Long, Long>()
        for (credit in transactions.filter { !it.isDeleted && isBonusCredit(it) }.sortedBy { it.dateTime }) {
            val purchase = purchases.firstOrNull { p ->
                p.id !in paired && p.accountNumber == credit.accountNumber &&
                    // Statement rows carry only a date, so a same-day credit may be stamped earlier than its purchase.
                    !p.dateTime.toLocalDate().isAfter(credit.dateTime.toLocalDate()) &&
                    Duration.between(p.dateTime, credit.dateTime) <= CREDIT_WINDOW &&
                    (p.amount * RATE_PERCENT).movePointLeft(2).subtract(credit.amount).abs() <= TOLERANCE
            } ?: continue
            paired[purchase.id] = credit.id
        }
        return paired
    }
}
