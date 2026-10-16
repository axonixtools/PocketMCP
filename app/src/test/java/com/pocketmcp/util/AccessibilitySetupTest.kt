package com.pocketmcp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilitySetupTest {
    private val component = AccessibilitySetup.SERVICE_COMPONENT
    private val other = "com.other.app/com.other.app.Service"

    @Test
    fun isEnabledIn_nullValue_isFalse() {
        assertFalse(AccessibilitySetup.isEnabledIn(null, component))
    }

    @Test
    fun isEnabledIn_emptyValue_isFalse() {
        assertFalse(AccessibilitySetup.isEnabledIn("", component))
    }

    @Test
    fun isEnabledIn_singleEntry_matches() {
        assertTrue(AccessibilitySetup.isEnabledIn(component, component))
    }

    @Test
    fun isEnabledIn_colonList_matchesIgnoringCaseAndSpaces() {
        assertTrue(AccessibilitySetup.isEnabledIn("$other : ${component.uppercase()}", component))
    }

    @Test
    fun isEnabledIn_partialName_doesNotMatch() {
        val partial = "com.pocketmcp/com.pocketmcp.accessibility.PhoneAccessibility"
        assertFalse(AccessibilitySetup.isEnabledIn(partial, component))
    }

    @Test
    fun merge_addsToEmpty() {
        assertEquals(listOf(component), AccessibilitySetup.mergeComponent(null, component, true))
    }

    @Test
    fun merge_appendsToExisting() {
        assertEquals(listOf(other, component), AccessibilitySetup.mergeComponent(other, component, true))
    }

    @Test
    fun merge_dedupesCaseInsensitive() {
        assertEquals(
            listOf(other),
            AccessibilitySetup.mergeComponent("$other:${component.uppercase()}", component, false)
        )
    }

    @Test
    fun merge_removesOnlyTarget() {
        assertEquals(listOf(other), AccessibilitySetup.mergeComponent("$other:$component", component, false))
    }

    @Test
    fun merge_disableMissing_isNoOp() {
        assertEquals(listOf(other), AccessibilitySetup.mergeComponent(other, component, false))
    }
}
