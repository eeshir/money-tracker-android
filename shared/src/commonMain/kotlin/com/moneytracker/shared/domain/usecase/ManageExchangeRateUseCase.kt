package com.moneytracker.shared.domain.usecase

import com.moneytracker.shared.data.local.entity.SharedExchangeRateEntity
import com.moneytracker.shared.data.repository.SharedExchangeRateRepository
import com.moneytracker.shared.core.SharedTimeConstants
import com.moneytracker.shared.data.util.currentTimeMillis

class ManageExchangeRateUseCase(
    private val repository: SharedExchangeRateRepository
) {
    suspend fun upsertRate(
        fromCurrency: String,
        toCurrency: String,
        rateMicros: Long,
        ttlMillis: Long = SharedTimeConstants.MILLIS_PER_DAY
    ) {
        val now = currentTimeMillis()
        repository.upsert(
            SharedExchangeRateEntity(
                fromCurrency = fromCurrency.uppercase(),
                toCurrency = toCurrency.uppercase(),
                rateMicros = rateMicros,
                provider = "manual",
                updatedAtEpochMillis = now,
                expiresAtEpochMillis = now + ttlMillis
            )
        )
    }
}
