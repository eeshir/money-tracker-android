package com.moneytracker.app.data.statement.gmail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailStatementClientTest {

    @Test
    fun `the search starts at the given time, looks at subjects and leaves out investment senders`() {
        val query = GmailStatementClient.searchQuery(1_700_000_000_000L)
        assertTrue("after:1700000000 " in query)
        assertTrue("subject:(statement OR estatement OR e-statement)" in query)
        assertTrue("-from:zerodha.com" in query)
        assertTrue("-from:camsonline.com" in query)
        assertTrue("-subject:\"mutual fund\"" in query)
    }

    @Test
    fun `terms and fact sheets riding along with a statement are not statements`() {
        assertFalse(GmailStatementClient.isStatementFile("Key Fact Statement.pdf"))
        assertFalse(GmailStatementClient.isStatementFile("KFS_credit_card.pdf"))
        assertFalse(GmailStatementClient.isStatementFile("MITC.pdf"))
        assertFalse(GmailStatementClient.isStatementFile("Terms_and_Conditions.pdf"))
        assertFalse(GmailStatementClient.isStatementFile("schedule-of-charges.pdf"))
    }

    @Test
    fun `statement file names pass`() {
        assertTrue(GmailStatementClient.isStatementFile("4315XXXXXXXX8000_Oct2026.pdf"))
        assertTrue(GmailStatementClient.isStatementFile("Credit_Card_Statement_Oct_2026.pdf"))
        assertTrue(GmailStatementClient.isStatementFile("5001234567_01092026_30092026.pdf"))
        assertTrue(GmailStatementClient.isStatementFile("eStatement.pdf"))
    }
}
