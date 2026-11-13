package com.moneytracker.shared.data.local

expect class SharedDatabaseFactory() {
    fun createDatabase(): SharedDatabase
}
