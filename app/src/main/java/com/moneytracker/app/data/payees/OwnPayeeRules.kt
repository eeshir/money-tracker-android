package com.moneytracker.app.data.payees

import android.content.Context
import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * "This payee is one of my own accounts." A debit whose payee contains [payee],
 * from [sourceBank]/[sourceLast4] (any account when unset), is money moving
 * between the person's own accounts or paying their own card:
 *  - the credit on the other account is matched with it when that account's SMS
 *    announced one, and
 *  - when it didn't, and [targetBank]/[targetLast4] is set, the missing credit is
 *    recorded on that account.
 * With no target the rule only pairs (a card-bill service like CRED pays whichever
 * card the person picks, so the card is found by the matching payment).
 */
@Serializable
data class OwnPayeeRule(
    val id: String = UUID.randomUUID().toString(),
    /** Lower-cased text the payee name contains. */
    val payee: String,
    val sourceBank: String? = null,
    val sourceLast4: String? = null,
    val targetBank: String? = null,
    val targetLast4: String? = null
) {
    val hasTarget: Boolean get() = targetBank != null && targetLast4 != null
}

/** The person's own-account payee rules, kept on the device. */
@Singleton
class OwnPayeeRuleStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("own_payee_rules", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun all(): List<OwnPayeeRule> =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString<List<OwnPayeeRule>>(it) }.getOrNull() }.orEmpty()

    fun add(rule: OwnPayeeRule) = write(all() + rule.copy(payee = rule.payee.trim().lowercase()))

    fun remove(id: String) = write(all().filterNot { it.id == id })

    /** Adds rules from a backup that aren't here yet (same payee, source and target); repeat-safe. */
    fun restore(rules: List<OwnPayeeRule>) {
        fun OwnPayeeRule.sameAs(other: OwnPayeeRule) =
            payee == other.payee && sourceBank == other.sourceBank && sourceLast4 == other.sourceLast4 &&
                targetBank == other.targetBank && targetLast4 == other.targetLast4
        val current = all()
        val added = rules.filter { incoming -> current.none { it.sameAs(incoming) } }
        if (added.isNotEmpty()) write(current + added)
    }

    private fun write(rules: List<OwnPayeeRule>) {
        prefs.edit().putString(KEY, json.encodeToString(rules)).apply()
    }

    private companion object {
        const val KEY = "rules"
    }
}

/** Which rule a debit falls under, and which credit on another account it pairs with. */
object OwnPayeeMatcher {

    /** Days either side of a debit that its credit may be dated (bank value dates differ). */
    const val PAIR_WINDOW_DAYS = 3L

    /**
     * The rule for money going out of an account to [debit]'s payee: the most
     * specific match (one naming the source account beats one for any account).
     */
    fun ruleFor(rules: List<OwnPayeeRule>, debit: TransactionEntity): OwnPayeeRule? {
        if (debit.transactionType != TransactionType.EXPENSE) return null
        val payee = debit.merchantName.lowercase()
        return rules
            .filter { payee.contains(it.payee) && it.payee.isNotBlank() }
            .filter { rule ->
                (rule.sourceBank == null || rule.sourceBank.equals(debit.bankName, ignoreCase = true)) &&
                    (rule.sourceLast4 == null || rule.sourceLast4 == debit.accountNumber)
            }
            .maxByOrNull { listOf(it.sourceBank, it.sourceLast4, it.targetBank).count { field -> field != null } }
    }

    /**
     * The credit that is the other side of [debit]: an income of the same amount on a
     * different account (the rule's target, when it names one), dated within
     * [PAIR_WINDOW_DAYS], not already taken by another debit. The closest date wins.
     */
    fun counterpartOf(
        debit: TransactionEntity,
        rule: OwnPayeeRule,
        candidates: List<TransactionEntity>,
        taken: Set<Long>
    ): TransactionEntity? =
        candidates.asSequence()
            .filter { it.transactionType == TransactionType.INCOME && it.id !in taken && it.id != debit.id }
            .filter { it.amount.compareTo(debit.amount) == 0 }
            .filter { !(it.bankName.equals(debit.bankName, ignoreCase = true) && it.accountNumber == debit.accountNumber) }
            .filter {
                !rule.hasTarget || (it.bankName.equals(rule.targetBank, ignoreCase = true) && it.accountNumber == rule.targetLast4)
            }
            .filter { abs(ChronoUnit.DAYS.between(it.dateTime.toLocalDate(), debit.dateTime.toLocalDate())) <= PAIR_WINDOW_DAYS }
            .minByOrNull { abs(ChronoUnit.SECONDS.between(it.dateTime, debit.dateTime)) }
}
