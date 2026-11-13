package com.moneytracker.shared.domain.usecase

import com.moneytracker.shared.data.local.entity.SharedUnrecognizedSmsEntity
import com.moneytracker.shared.data.repository.SharedUnrecognizedSmsRepository
import com.moneytracker.shared.data.util.currentTimeMillis

class ManageUnrecognizedSmsUseCase(
    private val repository: SharedUnrecognizedSmsRepository
) {
    suspend fun add(sender: String, smsBody: String): Long {
        val now = currentTimeMillis()
        return repository.insert(
            SharedUnrecognizedSmsEntity(
                sender = sender,
                smsBody = smsBody,
                receivedAtEpochMillis = now,
                createdAtEpochMillis = now
            )
        )
    }
}
