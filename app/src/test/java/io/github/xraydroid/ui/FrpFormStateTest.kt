package io.github.xraydroid.ui

import io.github.xraydroid.runtime.FrpConfigDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrpFormStateTest {
    @Test
    fun deletingOneRuleKeepsOtherRulesRawInputAndExpansion() {
        val original = FrpConfigDocument.parse("").addRule("proxies", "tcp").addRule("proxies", "tcp")
        val removedPrefix = "proxies/${original.ruleId("proxies", 0)}"
        val retainedPrefix = "proxies/${original.ruleId("proxies", 1)}"
        val state = FrpFormState()
        state.rawInputs["$removedPrefix/localPort"] = "-"
        state.errors["$removedPrefix/localPort"] = true
        state.rawInputs["$retainedPrefix/localPort"] = "invalid"
        state.errors["$retainedPrefix/localPort"] = true
        state.expandedSections[retainedPrefix] = true
        state.removeRuleInputs(removedPrefix)
        val remaining = original.removeRule("proxies", 0)
        assertEquals(retainedPrefix, "proxies/${remaining.ruleId("proxies", 0)}")
        assertEquals("invalid", state.rawInputs["$retainedPrefix/localPort"])
        assertEquals(true, state.expandedSections[retainedPrefix])
        assertFalse(state.rawInputs.containsKey("$removedPrefix/localPort"))
        assertFalse(state.isValid)
        state.removeRuleInputs(retainedPrefix)
        assertTrue(state.isValid)
    }

    @Test
    fun clearingForNewDocumentRemovesAllSessionState() {
        val state = FrpFormState()
        state.rawInputs["common/serverPort"] = "invalid"
        state.errors["common/serverPort"] = true
        state.expandedSections["common/connection"] = true
        state.newRuleTypes["proxies"] = "http"
        state.clear()
        assertTrue(state.isValid)
        assertTrue(state.rawInputs.isEmpty())
        assertTrue(state.expandedSections.isEmpty())
        assertTrue(state.newRuleTypes.isEmpty())
    }
}
