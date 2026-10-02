package io.github.xraydroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrpConnectionStatusTest {
    @Test
    fun missingStatusAndStopClearStaleConnectionAndProxies() {
        val connected = FrpRuntimeStatus(
            FrpConnectionStatus(FrpConnectionPhase.CONNECTED),
            listOf(FrpProxyStatus("fixture", "tcp", "running", ":16000"))
        )
        try {
            FrpStore.transition(FrpPhase.RUNNING, "")
            FrpStore.updateRuntime(connected)
            assertEquals(FrpConnectionPhase.CONNECTED, FrpStore.state.value.connection.phase)
            FrpStore.updateRuntime(null)
            assertEquals(FrpConnectionPhase.UNKNOWN, FrpStore.state.value.connection.phase)
            assertTrue(FrpStore.state.value.proxies.isEmpty())
            FrpStore.updateRuntime(connected)
            FrpStore.transition(FrpPhase.STOPPED, "")
            FrpStore.updateRuntime(connected)
            assertEquals(FrpConnectionPhase.UNKNOWN, FrpStore.state.value.connection.phase)
            assertTrue(FrpStore.state.value.proxies.isEmpty())
        } finally {
            FrpStore.transition(FrpPhase.STOPPED, "")
        }
    }

    @Test
    fun unknownErrorNeverDisplaysRawServerContent() {
        val status = FrpConnectionStatus(FrpConnectionPhase.RETRYING, "private-token-and-server-response")
        assertFalse(status.detail.contains("private-token"))
        assertTrue(status.detail.contains("自動重試"))
    }

    @Test
    fun loginRejectionDoesNotClaimAuthenticationIsTheOnlyCause() {
        val status = FrpConnectionStatus(FrpConnectionPhase.RETRYING, "login_rejected")
        assertTrue(status.detail.contains("拒絕登入"))
        assertTrue(status.detail.contains("登入限制"))
    }

    @Test
    fun connectedStateDistinguishesLoginFromForwarding() {
        assertTrue(FrpConnectionStatus(FrpConnectionPhase.CONNECTED).detail.contains("代理狀態"))
        assertEquals("代理已啟用", frpProxyStatusLabel("running"))
        assertTrue(frpProxyStatusLabel("start error").contains("啟動失敗"))
        assertEquals("代理狀態未知", frpProxyStatusLabel("private-server-response"))
    }
}
