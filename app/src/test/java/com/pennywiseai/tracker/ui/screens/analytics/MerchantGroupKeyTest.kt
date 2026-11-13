package com.pennywiseai.tracker.ui.screens.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class MerchantGroupKeyTest {

    @Test
    fun `a UPI id aliased to a shop's name groups with the bank's spelling of that shop`() {
        val aliases = mapOf("*ab1cd@xyz" to "Corner Dairy")
        assertEquals(merchantGroupKey("CORNER DAIRY", aliases), merchantGroupKey("*ab1cd@xyz", aliases))
    }

    @Test
    fun `without an alias the name is only brand-grouped`() {
        assertEquals("*ab1cd@xyz", merchantGroupKey("*ab1cd@xyz", emptyMap()))
        assertEquals("Swiggy", merchantGroupKey("SWIGGY LIMITED", emptyMap()))
    }

    @Test
    fun `a blank alias is ignored`() {
        assertEquals("Corner Dairy", merchantGroupKey("Corner Dairy", mapOf("Corner Dairy" to " ")))
    }
}
