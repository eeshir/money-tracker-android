package com.moneytracker.shared.data.statement

interface SharedStatementParser {
    fun canHandle(text: String): Boolean
    fun parse(text: String): List<SharedParsedStatementTransaction>
}
