package com.moneytracker.app.data.accounts

import com.moneytracker.app.data.database.entity.AccountBalanceEntity
import java.math.BigDecimal

/**
 * Banks often give all of a person's cards one credit limit. Each card still owes its own amount, but what one
 * card spends is gone for the others too: available credit is the limit less what they owe together, and the limit
 * counts once. Sharing is only what the person switched on for a bank, never guessed from equal limits.
 */
object SharedLimits {
    /** One limit shared by [cards] cards that owe [used] together. */
    data class Pool(val limit: BigDecimal, val used: BigDecimal, val cards: Int) {
        val available: BigDecimal get() = limit - used
        val usedPercent: Int get() = if (limit.signum() > 0) (used.toDouble() / limit.toDouble() * 100).toInt() else 0
    }

    fun bankKey(bankName: String): String = bankName.trim().lowercase()

    /** Cards that can share a limit with [card]: the same bank and currency, [card] included. */
    fun siblings(card: AccountBalanceEntity, cards: List<AccountBalanceEntity>): List<AccountBalanceEntity> =
        cards.filter { bankKey(it.bankName) == bankKey(card.bankName) && it.currency == card.currency }

    /**
     * The pool [card] draws on, or null when it has its own limit. The limit is the largest one recorded among the
     * pool's cards: a card whose limit was never updated can only be showing less than the shared one.
     */
    fun poolOf(card: AccountBalanceEntity, cards: List<AccountBalanceEntity>, sharedBanks: Set<String>): Pool? {
        if (bankKey(card.bankName) !in sharedBanks) return null
        val group = siblings(card, cards)
        if (group.size < 2) return null
        val limit = group.mapNotNull { it.creditLimit }.maxOrNull() ?: return null
        return Pool(limit, group.fold(BigDecimal.ZERO) { sum, c -> sum + c.balance }, group.size)
    }

    /** Credit still available on [cards] (all in one display currency), with each shared limit counted once. */
    fun totalAvailable(cards: List<AccountBalanceEntity>, sharedBanks: Set<String>): BigDecimal {
        val counted = HashSet<Pair<String, String>>()
        return cards.fold(BigDecimal.ZERO) { total, card ->
            val pool = poolOf(card, cards, sharedBanks)
            when {
                pool == null -> total + (card.creditLimit ?: BigDecimal.ZERO) - card.balance
                counted.add(bankKey(card.bankName) to card.currency) -> total + pool.available
                else -> total
            }
        }
    }
}
