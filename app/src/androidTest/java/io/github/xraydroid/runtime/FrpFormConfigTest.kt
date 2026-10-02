package io.github.xraydroid.runtime

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrpFormConfigTest {
    private lateinit var context: Context

    @Before
    fun initializeIsolatedStore() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "io.github.xraydroid.validation") {
            "FRP form verification requires the isolated validation application ID"
        }
        FrpStore.initialize(context)
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!FrpStore.state.value.loaded && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("FRP settings must finish loading", FrpStore.state.value.loaded)
    }

    @Test
    fun everyProxyAndVisitorTypePassesOfficialVerification() = runBlocking(Dispatchers.IO) {
        val proxyTypes = listOf("tcp", "udp", "http", "https", "stcp", "sudp", "xtcp", "tcpmux")
        val visitorTypes = listOf("stcp", "sudp", "xtcp")
        assertEquals(proxyTypes.toSet(), FrpConfigDocument.proxyTypes.toSet())
        assertEquals(visitorTypes.toSet(), FrpConfigDocument.visitorTypes.toSet())
        var document = baseline()
        proxyTypes.forEachIndexed { index, type ->
            document = document.addRule("proxies", type)
                .updateRule("proxies", index, "name", "fixture-$type")
                .updateRule("proxies", index, "localPort", 18080L)
                .updateRule("proxies", index, "enabled", true)
                .updateRule("proxies", index, "transport.useEncryption", true)
                .updateRule("proxies", index, "transport.useCompression", true)
                .updateRule("proxies", index, "transport.bandwidthLimit", "10MB")
                .updateRule("proxies", index, "transport.bandwidthLimitMode", "client")
                .updateRule("proxies", index, "transport.proxyProtocolVersion", "v1")
                .updateRule("proxies", index, "loadBalancer.group", "fixture")
                .updateRule("proxies", index, "loadBalancer.groupKey", "disposable-fixture-value")
                .updateRule("proxies", index, "healthCheck.type", "http")
                .updateRule("proxies", index, "healthCheck.path", "/fixture")
                .updateRule("proxies", index, "healthCheck.httpHeaders", listOf(mapOf("name" to "X-Fixture", "value" to "preserved")))
                .updateRule("proxies", index, "metadatas", mapOf("fixture" to "value"))
                .updateRule("proxies", index, "annotations", mapOf("fixture.example.invalid/key" to "value"))
            when (type) {
                "tcp", "udp" -> document = document.updateRule("proxies", index, "remotePort", 18081L + index)
                "http", "https", "tcpmux" -> document = document.updateRule(
                    "proxies", index, "customDomains", listOf("$type.example.invalid")
                )
                else -> document = document.updateRule("proxies", index, "secretKey", "disposable-fixture-value")
            }
            if (type == "tcpmux") document = document.updateRule("proxies", index, "multiplexer", "httpconnect")
            if (type in listOf("stcp", "sudp", "xtcp")) document = document.updateRule("proxies", index, "allowUsers", listOf("fixture"))
            if (type == "xtcp") document = document.updateRule("proxies", index, "natTraversal.disableAssistedAddrs", true)
            assertTrue("The official verifier must accept a generated $type proxy", FrpStore.validateConfig(context, document.source))
        }
        visitorTypes.forEachIndexed { index, type ->
            document = document.addRule("visitors", type)
                .updateRule("visitors", index, "serverName", "fixture-$type")
                .updateRule("visitors", index, "secretKey", "disposable-fixture-value")
                .updateRule("visitors", index, "bindPort", 18100L + index)
                .updateRule("visitors", index, "enabled", true)
                .updateRule("visitors", index, "serverUser", "fixture")
                .updateRule("visitors", index, "transport.useEncryption", true)
                .updateRule("visitors", index, "transport.useCompression", true)
            if (type == "xtcp") {
                document = document.updateRule("visitors", index, "protocol", "quic")
                    .updateRule("visitors", index, "keepTunnelOpen", true)
                    .updateRule("visitors", index, "maxRetriesAnHour", 8L)
                    .updateRule("visitors", index, "minRetryInterval", 90L)
                    .updateRule("visitors", index, "fallbackTo", "fixture-stcp-visitor")
                    .updateRule("visitors", index, "fallbackTimeoutMs", 1000L)
                    .updateRule("visitors", index, "natTraversal.disableAssistedAddrs", true)
            }
            assertTrue("The official verifier must accept a generated $type visitor", FrpStore.validateConfig(context, document.source))
        }
        val reparsed = FrpConfigDocument.parse(document.source)
        assertEquals(proxyTypes.size, reparsed.rules("proxies").size)
        assertEquals(visitorTypes.size, reparsed.rules("visitors").size)
        val removed = reparsed.removeRule("proxies", 0).removeRule("visitors", 0)
        assertEquals(proxyTypes.drop(1), removed.rules("proxies").map { it["type"] })
        assertEquals(visitorTypes.drop(1), removed.rules("visitors").map { it["type"] })
        assertTrue("Removing rules must retain an officially valid configuration", FrpStore.validateConfig(context, removed.source))
    }

    @Test
    fun allTransportProtocolsPreserveAuthenticationScopesAndTlsSettings() = runBlocking(Dispatchers.IO) {
        val document = baseline()
            .set("auth.method", "token")
            .set("auth.token", "disposable-fixture-value")
            .set("auth.additionalScopes", listOf("HeartBeats", "NewWorkConns"))
            .set("transport.tls.enable", true)
            .set("transport.tls.serverName", "frps.example.invalid")
            .set("transport.tls.disableCustomTLSFirstByte", true)
            .set("transport.dialServerTimeout", 10L)
            .set("transport.dialServerKeepalive", 7200L)
            .set("transport.poolCount", 1L)
            .set("transport.tcpMux", true)
            .set("transport.tcpMuxKeepaliveInterval", 60L)
            .set("transport.heartbeatInterval", 30L)
            .set("transport.heartbeatTimeout", 90L)
        listOf("tcp", "kcp", "quic", "websocket", "wss").forEach { protocol ->
            var candidate = document.set("transport.protocol", protocol)
            if (protocol == "quic") {
                candidate = candidate.set("transport.quic.keepalivePeriod", 10L)
                    .set("transport.quic.maxIdleTimeout", 30L)
                    .set("transport.quic.maxIncomingStreams", 100000L)
            }
            val reparsed = FrpConfigDocument.parse(candidate.source)
            assertEquals(listOf("HeartBeats", "NewWorkConns"), reparsed.get("auth.additionalScopes"))
            assertEquals(true, reparsed.get("transport.tls.enable"))
            assertEquals("frps.example.invalid", reparsed.get("transport.tls.serverName"))
            assertEquals(true, reparsed.get("transport.tls.disableCustomTLSFirstByte"))
            assertTrue("The official verifier must accept the $protocol transport", FrpStore.validateConfig(context, candidate.source))
        }
    }

    @Test
    fun commonFormChangesPreserveIncludesPluginsAndProtocolOptions() = runBlocking(Dispatchers.IO) {
        val included = File.createTempFile("frp-form-fixture-", ".toml", context.cacheDir)
        try {
            included.writeText(
                """
                [[proxies]]
                name = "included-udp"
                type = "udp"
                localPort = 18080
                remotePort = 18085
                """.trimIndent()
            )
            val original = baseline()
                .set("includes", listOf(included.absolutePath))
                .set("auth.method", "token")
                .set("auth.token", "disposable-fixture-value")
                .set("auth.additionalScopes", listOf("HeartBeats"))
                .addRule("proxies", "http")
                .updateRule("proxies", 0, "customDomains", listOf("form.example.invalid"))
                .updateRule("proxies", 0, "locations", listOf("/fixture"))
                .updateRule("proxies", 0, "hostHeaderRewrite", "upstream.example.invalid")
                .updateRule("proxies", 0, "requestHeaders.set.X-Fixture", "preserved")
                .updateRule("proxies", 0, "plugin.type", "http_proxy")
                .updateRule("proxies", 0, "plugin.httpUser", "fixture-user")
                .updateRule("proxies", 0, "plugin.httpPassword", "disposable-fixture-value")
            assertTrue("The source fixture must be officially valid", FrpStore.validateConfig(context, original.source))
            val edited = FrpConfigDocument.parse(original.source)
                .set("serverPort", 17001L)
                .updateRule("proxies", 0, "name", "renamed-http")
            val reparsed = FrpConfigDocument.parse(edited.source)
            assertEquals(original.get("includes"), reparsed.get("includes"))
            assertEquals(original.get("auth"), reparsed.get("auth"))
            val before = original.rules("proxies").single().minus("name")
            val after = reparsed.rules("proxies").single().minus("name")
            assertTrue("Editing common fields must retain all protocol and plugin values", before == after)
            assertTrue("The edited fixture must remain officially valid", FrpStore.validateConfig(context, edited.source))
        } finally {
            included.delete()
        }
    }

    @Test
    fun invalidProtocolFieldsProduceGenericDiagnosticsWithoutFixtureValues() = runBlocking(Dispatchers.IO) {
        val marker = "disposable-invalid-fixture-value"
        val invalid = baseline().addRule("proxies", "tcpmux")
            .updateRule("proxies", 0, "multiplexer", marker)
        assertFalse("An unsupported multiplexer must fail official verification", FrpStore.validateConfig(context, invalid.source))
        val message = FrpStore.state.value.message
        assertEquals("設定驗證失敗，請檢查 TOML 語法、欄位與檔案路徑", message)
        assertFalse("Diagnostics must not expose raw backend field values", message.contains(marker))
        val invalidVisitor = baseline().addRule("visitors", "xtcp")
            .updateRule("visitors", 0, "serverName", "fixture-xtcp")
            .updateRule("visitors", 0, "protocol", "tcp")
        assertFalse("An XTCP visitor must reject unsupported TCP rendezvous", FrpStore.validateConfig(context, invalidVisitor.source))
    }

    private fun baseline(): FrpConfigDocument = FrpConfigDocument.parse("serverAddr = \"127.0.0.1\"\nserverPort = 17000\n")
}
