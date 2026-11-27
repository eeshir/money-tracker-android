package com.moneytracker.app.data.rewards

/**
 * Corrections to suggested rules that also have to reach rules already saved from them. Only ever adds:
 * a keyword or a rule the person does not have. Their rates, caps and own entries are left as they are.
 */
object RewardRuleUpgrades {
    const val VERSION = 3

    fun apply(program: CardRewardProgram): CardRewardProgram {
        val bank = program.bankName.lowercase()
        return when {
            // Ajio is shopping, which HSBC Live+ pays at the higher rate; its card SMS names the seller, "Reliance Retail Ltd".
            "hsbc" in bank && program.name == "HSBC Live+" -> program.copy(
                rules = program.rules.map { rule ->
                    if (rule.extraKeywords.none { it.equals("shopping", ignoreCase = true) }) rule
                    else rule.copy(extraKeywords = rule.extraKeywords + HSBC_SHOPPING.filter { it !in rule.extraKeywords })
                }
            )
            // First in the list, so it wins over the partner rule that also names Titan.
            "hdfc" in bank && program.name == "Tata Neu Plus" && program.rules.none { "emi titan" in it.keywords } ->
                program.copy(rules = listOf(RewardRule("EMI at Titan", listOf("emi titan"), "1")) + program.rules)
            "hdfc" in bank && program.name == "MoneyBack+" -> program.copy(
                rules = program.rules.map { rule ->
                    if (rule.label != "10X partners") rule else rule.copy(
                        monthlyCap = rule.monthlyCap ?: "625",
                        keywords = if (rule.keywords.any { it.equals("swiggy", ignoreCase = true) }) rule.keywords else rule.keywords + "swiggy"
                    )
                }
            )
            else -> program
        }
    }

    val HSBC_SHOPPING = listOf("ajio", "reliance retail")
}
