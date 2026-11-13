package com.pennywiseai.tracker.data.rewards

import com.pennywiseai.tracker.data.statement.IciciCreditCardPdfParser
import com.pennywiseai.tracker.data.statement.StatementRewardText
import com.pennywiseai.tracker.presentation.rewards.CardRewardsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.random.Random

class RewardRoundTripTest {

    @Test
    fun `every suggested rule survives the editor's text form unchanged`() {
        // "Use suggested rates" then Save turns rules into text and back: nothing may be lost on the way.
        for (bank in listOf("HSBC Bank", "HDFC Bank", "ICICI Bank")) {
            assertNotNull(bank, CardRewardPresets.forCard(bank))
            for (preset in CardRewardPresets.suggestionsFor(bank)) {
                val text = CardRewardsViewModel.rulesToText(preset.rules)
                assertEquals("${preset.name} rules changed through the editor text", preset.rules, CardRewardsViewModel.parseRules(text))
            }
        }
    }

    @Test
    fun `random rules survive the editor's text form`() {
        val rnd = Random(5)
        fun words() = (0 until rnd.nextInt(1, 4)).map { "kw${rnd.nextInt(100)}" }
        fun maybe(v: String) = if (rnd.nextBoolean()) v else null
        repeat(500) { n ->
            val rule = RewardRule(
                label = "label${rnd.nextInt(10)}", keywords = words(), ratePercent = rnd.nextInt(0, 20).toString(),
                monthlyCap = maybe("1200"),
                earlierMonthlyCap = maybe("1000"), earlierUntil = maybe("2026-07-26"),
                extraKeywords = if (rnd.nextBoolean()) words() else emptyList(), extraFrom = maybe("2026-07-26"),
                exceptKeywords = if (rnd.nextBoolean()) words() else emptyList(),
                aboveCapPercent = maybe("0"), earlierAboveCapPercent = maybe("1.5")
            )
            // A cap column must exist for the columns after it to line up; the editor only writes that shape.
            val shaped = if (rule.monthlyCap == null && (rule.earlierMonthlyCap != null || rule.earlierUntil != null || rule.extraFrom != null ||
                    rule.extraKeywords.isNotEmpty() || rule.exceptKeywords.isNotEmpty() || rule.aboveCapPercent != null || rule.earlierAboveCapPercent != null)
            ) rule.copy(monthlyCap = "1") else rule
            assertEquals("case $n", listOf(shaped), CardRewardsViewModel.parseRules(CardRewardsViewModel.rulesToText(listOf(shaped))))
        }
    }

    @Test
    fun `the statement readers never crash on odd text`() {
        val rnd = Random(99)
        val pieces = listOf(
            "Opening Balance Earned Redeemed Closing", "1,234 56 0 1,290", "Reward Points", "CASHBACK CREDIT 12.34 CR", "05AUG CASHBACK CREDIT 1,200.00 CR",
            "04/10/2026 11111111111 SOME SHOP 50 1,000.00", "-15", "Points Summary", "Total 12 pts", "EARNINGS", "Earned", "\u0000", "₹", "  ", "\t", "999999999999999999999",
            "Cash Back Summary", "SR NO. TRANSACTION AMOUNT", "2CashPoints per 200 onOther Spends 34 pts", "Reward points earned: 1,25", "NaN", "--", "1.2.3.4"
        )
        repeat(2000) {
            val text = (0 until rnd.nextInt(0, 30)).joinToString("\n") { pieces[rnd.nextInt(pieces.size)] }
            StatementRewardText.earned(text)
            StatementRewardText.cashbackCredits(text)
            StatementRewardText.relevantLines(text)
            IciciCreditCardPdfParser().rowRewardDetails(text)
            IciciCreditCardPdfParser().rowRewards(text)
        }
    }
}
