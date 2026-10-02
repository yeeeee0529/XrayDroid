package io.github.xraydroid.runtime

import androidx.annotation.StringRes
import io.github.xraydroid.R
import java.io.File
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
            FrpStore.transition(FrpPhase.RUNNING, null)
            FrpStore.updateRuntime(connected)
            assertEquals(FrpConnectionPhase.CONNECTED, FrpStore.state.value.connection.phase)
            FrpStore.updateRuntime(null)
            assertEquals(FrpConnectionPhase.UNKNOWN, FrpStore.state.value.connection.phase)
            assertTrue(FrpStore.state.value.proxies.isEmpty())
            FrpStore.updateRuntime(connected)
            FrpStore.transition(FrpPhase.STOPPED, null)
            FrpStore.updateRuntime(connected)
            assertEquals(FrpConnectionPhase.UNKNOWN, FrpStore.state.value.connection.phase)
            assertTrue(FrpStore.state.value.proxies.isEmpty())
        } finally {
            FrpStore.transition(FrpPhase.STOPPED, null)
        }
    }

    @Test
    fun unknownErrorNeverDisplaysRawServerContent() {
        val status = FrpConnectionStatus(FrpConnectionPhase.RETRYING, "private-token-and-server-response")
        assertEquals(TextResource(R.string.frp_connection_detail_retrying), status.detail)
        assertTrue(textOf(status.detail.id).contains("自動重試"))
    }

    @Test
    fun loginRejectionDoesNotClaimAuthenticationIsTheOnlyCause() {
        val detail = FrpConnectionStatus(FrpConnectionPhase.RETRYING, "login_rejected").detail
        assertTrue(textOf(detail.id).contains("拒絕登入"))
        assertTrue(textOf(detail.id).contains("登入限制"))
    }

    @Test
    fun closedConnectionPointsAtTcpMuxMismatch() {
        val detail = FrpConnectionStatus(FrpConnectionPhase.RETRYING, "closed").detail
        assertTrue(textOf(detail.id).contains("tcpMux"))
        assertFalse(textOf(detail.id).contains("連線或交握失敗"))
    }

    @Test
    fun everyKnownErrorCodeHasItsOwnMessage() {
        // 核心分類與顯示文字必須一對一；漏掉任何一個都會靜默退回泛用訊息。
        assertEquals(9, errorDetails.size)
        assertEquals(9, errorDetails.values.toSet().size)
        val generic = FrpConnectionStatus(FrpConnectionPhase.RETRYING, "connection").detail
        for ((code, message) in errorDetails) {
            assertEquals(TextResource(message), FrpConnectionStatus(FrpConnectionPhase.RETRYING, code).detail)
            if (code != "connection") assertFalse("$code falls back to the generic message", TextResource(message) == generic)
            assertTrue("$code must have text in strings.xml", textOf(message).isNotBlank())
        }
    }

    @Test
    fun connectedStateDistinguishesLoginFromForwarding() {
        assertTrue(textOf(FrpConnectionStatus(FrpConnectionPhase.CONNECTED).detail.id).contains("代理狀態"))
        assertEquals(R.string.frp_proxy_status_running, frpProxyStatusLabel("running"))
        assertTrue(textOf(frpProxyStatusLabel("start error")).contains("啟動失敗"))
        assertEquals(R.string.frp_proxy_status_unknown, frpProxyStatusLabel("private-server-response"))
    }

    // 單元測試沒有 Android 資源；直接比對 strings.xml，確保資源 ID 真的對應到預期文案。
    private fun textOf(@StringRes id: Int): String {
        val name = R.string::class.java.fields.firstOrNull { it.getInt(null) == id }?.name
            ?: error("Unknown string resource id: $id")
        val entry = Regex("<string name=\"$name\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(stringsXml)
            ?: error("Missing strings.xml entry: $name")
        return entry.groupValues[1]
    }

    private val stringsXml: String by lazy {
        listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml"))
            .firstOrNull(File::isFile)
            ?.readText()
            ?: error("strings.xml not found from ${File(".").absolutePath}")
    }
}
