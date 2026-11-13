package com.pennywiseai.tracker.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitlementGateTest {
    @Test fun `every feature is unlocked from the first read`() {
        val gate = EntitlementGate()
        assertTrue(gate.isProEntitled.value)
        assertEquals(true, gate.resolvedProEntitlement.value)
    }
}
