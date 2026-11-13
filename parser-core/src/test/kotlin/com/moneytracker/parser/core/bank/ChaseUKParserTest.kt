package com.moneytracker.parser.core.bank

import com.moneytracker.parser.core.TransactionType
import com.moneytracker.parser.core.test.ExpectedTransaction
import com.moneytracker.parser.core.test.ParserTestCase
import com.moneytracker.parser.core.test.ParserTestUtils
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal

class ChaseUKParserTest {

    @TestFactory
    fun `chase uk app notifications`(): List<DynamicTest> {
        val cases = listOf(
            ParserTestCase(
                name = "Money in (app notification)",
                message = "🎉 £0.01 just landed in Test's Account from Jane Doe",
                sender = "ChaseUK",
                expected = ExpectedTransaction(
                    amount = BigDecimal("0.01"),
                    currency = "GBP",
                    type = TransactionType.INCOME,
                    merchant = "Jane Doe"
                )
            ),
            ParserTestCase(
                name = "Money in, larger amount with thousands separator",
                message = "🎉 £1,250.00 just landed in Test's Account from ACME LTD.",
                sender = "ChaseUK",
                expected = ExpectedTransaction(
                    amount = BigDecimal("1250.00"),
                    currency = "GBP",
                    type = TransactionType.INCOME,
                    merchant = "ACME LTD"
                )
            )
        )
        val handleCases = listOf(
            "ChaseUK" to true,
            "CHASE_UK" to true,
            "Chase" to false,   // the US bank — must stay with ChaseBankParser
            "24273" to false
        )
        return ParserTestUtils.runTestSuite(ChaseUKParser(), cases, handleCases, "Chase UK")
    }
}
