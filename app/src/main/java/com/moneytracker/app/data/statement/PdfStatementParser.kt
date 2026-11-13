package com.moneytracker.app.data.statement

import com.moneytracker.parser.core.ParsedTransaction

interface PdfStatementParser {
    fun canHandle(text: String): Boolean
    fun parse(text: String): List<ParsedTransaction>
}
