package com.pennywiseai.tracker.data.rewards

/**
 * Starting rates for cards the app knows about, offered pre-filled in the edit dialog.
 * They come from the banks' published terms as read on 2026-11-02 and card terms change often
 * (and third-party sources disagree), so they are only a suggestion: the person checks and saves them.
 */
object CardRewardPresets {

    /** [p] with the point value filled in from the suggestion for its bank when it was saved without one. */
    fun withDefaults(p: CardRewardProgram): CardRewardProgram {
        if (p.pointValue != null) return p
        // The suggestion for the same card variant (by name), not just the bank's first one.
        val suggestions = suggestionsFor(p.bankName)
        val match = suggestions.firstOrNull { it.name.equals(p.name.trim(), ignoreCase = true) } ?: suggestions.firstOrNull()
        return match?.pointValue?.let { p.copy(pointValue = it) } ?: p
    }

    /** A suggestion for the card of [bankName], or null when none is known. */
    fun forCard(bankName: String): CardRewardProgram? = suggestionsFor(bankName).firstOrNull()

    /**
     * Every suggestion for [bankName]'s cards, the most common first. A bank can issue several cards the app cannot
     * tell apart from SMS (HDFC: MoneyBack+ and Tata Neu Plus), so the person picks the one they hold.
     */
    fun suggestionsFor(bankName: String): List<CardRewardProgram> {
        val bank = bankName.lowercase()
        return when {
            "hsbc" in bank -> listOf(hsbcLivePlus(bankName))
            "hdfc" in bank -> listOf(hdfcMoneyBackPlus(bankName), hdfcTataNeuPlus(bankName))
            "icici" in bank -> listOf(amazonPayIcici(bankName))
            else -> emptyList()
        }.map { it.copy(cardLast4 = "") }
    }

    /**
     * HDFC Tata Neu Plus (terms as the card owner reported them; not yet checked against a statement): rewards come as
     * NeuCoins, one worth Rs 1. 1% on everything else, including UPI payments to merchants (a UPI payment from this card
     * shows only the merchant's UPI id in the SMS, so it always lands here). 2% NeuCoins on Tata partner brands, plus a further
     * 5% when the purchase is made through the Tata Neu app: 7% in all. The SMS does not say whether the app was used, so
     * partners are suggested at 2% and a purchase made in the app is set to 7% with "Your rate" on that transaction.
     * Fuel, rent, wallet loads, insurance, utilities, gold, government payments and EMIs earn nothing. Monthly caps exist on
     * some categories but could not be confirmed, so none is set: add one in the editor once a statement shows it.
     */
    private fun hdfcTataNeuPlus(bank: String) = CardRewardProgram(
        bankName = bank, cardLast4 = "", name = "Tata Neu Plus",
        baseRatePercent = "1",
        pointValue = "1",
        rules = listOf(
            RewardRule(
                "Tata Neu partners",
                listOf(
                    "tata neu", "tataneu", "neupass", "bigbasket", "croma", "tata 1mg", "1mg", "westside", "trent",
                    "tata cliq", "tatacliq", "titan", "tanishq", "fastrack", "taj hotel", "vivanta", "ginger hotel", "ihcl",
                    "air india", "airasia", "starbucks", "qmin", "tata play", "tata sky", "tata power", "tata motors",
                    "tata ev", "tata digital", "tata consumer"
                ),
                "2"
            )
        ),
        excludedKeywords = listOf(
            // Phrases, not bare "rent"/"emi"/"tax", which would also match "current", "premium" and "taxi".
            "fuel", "rent payment", "house rent", "wallet", "insurance", "utilit", "electricity", "gold", "government",
            "income tax", "emi conversion", "emi processing"
        )
    )

    /**
     * HSBC revised this card on 26 July 2026 (HSBC's own notice was not available; this follows card-news sources and a
     * summary of the revised terms the card owner supplied):
     * - Before: 10% on dining, food delivery and groceries up to Rs 1,000 a cycle (spend past that earned the 1.5% base);
     *   1.5% elsewhere, including abroad.
     * - From 26 July 2026: shopping, utilities and telecom join the 10%; Amazon, Flipkart and Myntra stay at 1.5% (Amazon
     *   Fresh and Flipkart Minutes keep 10% as groceries); one cap of Rs 1,200 a statement cycle for all of them, and spend past
     *   it earns nothing; international spends and hospital and local-transit spends earn nothing.
     */
    private fun hsbcLivePlus(bank: String) = CardRewardProgram(
        bankName = bank, cardLast4 = "", name = "HSBC Live+",
        baseRatePercent = "1.5",
        rules = listOf(
            RewardRule(
                label = "10% categories",
                keywords = listOf(
                    "dining", "restaurant", "cafe", "food", "grocer", "swiggy", "zomato", "blinkit", "zepto",
                    "bigbasket", "instamart", "dmart", "d mart", "avenue supermarts", "supermarket",
                    "amazon fresh", "flipkart minutes"
                ),
                ratePercent = "10", monthlyCap = "1200", earlierMonthlyCap = "1000", earlierUntil = "2026-07-26",
                extraKeywords = listOf(
                    "shopping", "nykaa", "utilit", "electricity", "telecom", "jio", "airtel", "vodafone", "broadband", "recharge"
                ),
                extraFrom = "2026-07-26",
                exceptKeywords = listOf("amazon", "flipkart", "myntra"),
                aboveCapPercent = "0", earlierAboveCapPercent = "1.5"
            )
        ),
        excludedKeywords = listOf("fuel", "rent", "wallet"),
        noRewardForeignFrom = "2026-07-26",
        laterExcludedKeywords = listOf("hospital", "metro", "transit"),
        laterExcludedFrom = "2026-07-26"
    )

    /**
     * Per the card's statements (points worth 25 paise): 2 CashPoints per Rs 200 on other spends (0.25%); 10X on partner
     * merchants (20 points per Rs 200, 2.5%): a Rs 15,356 Amazon purchase earned about 1,535 of a cycle's 1,600 bonus points.
     * Swiggy, Myntra and the rest earned only the base rate. Purchases made through SmartBuy earn a separate 5% that the bank
     * credits as a "SmartBuy_Bonus_5per_CB" row, which the app counts as the cashback it is. EMI conversions earn nothing.
     */
    private fun hdfcMoneyBackPlus(bank: String) = CardRewardProgram(
        bankName = bank, cardLast4 = "", name = "MoneyBack+",
        baseRatePercent = "0.25",
        pointValue = "0.25",
        rules = listOf(
            RewardRule("10X partners", listOf("amazon", "bigbasket", "flipkart", "reliance smart"), "2.5")
        ),
        excludedKeywords = listOf("fuel", "emi")
    )


    /** 5% on Amazon (Prime rate; non-Prime is 3%), 2% at Amazon Pay partners, 1% elsewhere. */
    private fun amazonPayIcici(bank: String) = CardRewardProgram(
        bankName = bank, cardLast4 = "", name = "Amazon Pay ICICI",
        baseRatePercent = "1",
        pointValue = "1",
        rules = listOf(
            // Per ICICI statement rows: recharges and Amazon Pay top-ups or vouchers paid 2%; Amazon.in, grocery and Pay ecom 5%
            // (Prime); everything else (Swiggy, Zomato, Blinkit, Myntra, travel) mostly 1%.
            RewardRule(
                "Amazon recharge, vouchers, balance",
                listOf("amazon pay in recharge", "amazon pay india pvt", "recharge", "gift card", "voucher", "steam"), "2"
            ),
            RewardRule("Amazon (Prime)", listOf("amazon"), "5")
        ),
        excludedKeywords = listOf("fuel", "rent", "emi", "school", "tuition", "tax", "government", "gold", "jewel")
    )
}
