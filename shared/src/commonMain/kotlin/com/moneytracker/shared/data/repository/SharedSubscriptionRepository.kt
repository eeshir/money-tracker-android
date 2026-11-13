package com.moneytracker.shared.data.repository

import com.moneytracker.shared.data.local.entity.SharedSubscriptionEntity
import kotlinx.coroutines.flow.Flow

interface SharedSubscriptionRepository {
    fun observeAll(): Flow<List<SharedSubscriptionEntity>>
    suspend fun upsert(subscription: SharedSubscriptionEntity): Long
    suspend fun deleteById(id: Long)
}
