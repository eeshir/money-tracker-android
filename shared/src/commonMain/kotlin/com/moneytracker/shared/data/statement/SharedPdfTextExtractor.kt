package com.moneytracker.shared.data.statement

expect object SharedPdfTextExtractor {
    fun extractText(filePath: String): String
}
