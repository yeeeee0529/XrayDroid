package io.github.xraydroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FrpConfigDocumentTest {
    @Test
    fun unchangedDocumentPreservesCommentsAndNotation() {
        val source = "# 保留註解\nserverPort = 0x1b58 # 連接埠\nserverAddr = 'example.com'\n"
        val document = FrpConfigDocument.parse(source)
        assertEquals(source, document.source)
        assertEquals(source, document.set("serverPort", 7000).source)
        assertEquals(source, document.set("missing.child", null).source)
    }

    @Test
    fun editingPreservesUnknownFieldsAndNestedRuleData() {
        val source = """
            includes = ["conf.d/*.toml"]
            serverAddr = "example.com"
            unknown = { nested = [1, { "dotted.key" = "keep" }] }
            [auth.tokenSource]
            type = "file"
            file.path = "/private/token"
            [[proxies]]
            name = "existing"
            type = "tcp"
            remotePort = 6000
            [proxies.plugin]
            type = "http_proxy"
            httpUser = "test-user"
            [proxies.metadatas]
            "literal.dot" = "persist"
            [[proxies]]
            name = "second"
            type = "udp"
            remotePort = 6001
            [[visitors]]
            name = "visitor"
            type = "stcp"
            serverName = "private"
        """.trimIndent()
        val original = FrpConfigDocument.parse(source)
        val edited = original.set("serverAddr", "new.example.com").updateRule("proxies", 0, "remotePort", 7001)
        val reparsed = FrpConfigDocument.parse(edited.source)
        assertEquals(edited.values, reparsed.values)
        assertEquals(original.get("includes"), reparsed.get("includes"))
        assertEquals(original.get("unknown"), reparsed.get("unknown"))
        assertEquals(original.get("auth.tokenSource"), reparsed.get("auth.tokenSource"))
        assertEquals(original.rules("visitors"), reparsed.rules("visitors"))
        assertEquals(original.rules("proxies")[0]["plugin"], reparsed.rules("proxies")[0]["plugin"])
        assertEquals(original.rules("proxies")[0]["metadatas"], reparsed.rules("proxies")[0]["metadatas"])
        assertEquals(7001L, reparsed.rules("proxies")[0]["remotePort"])
        assertEquals(6000L, original.rules("proxies")[0]["remotePort"])
    }

    @Test
    fun dottedLiteralKeysAndStringsAreEscapedWithoutChangingMeaning() {
        val document = FrpConfigDocument.parse("")
            .set("metadatas.\"a.b\"", "quote\" backslash\\ newline\n tab\t control\u0001")
            .set("metadatas.\"bracket]#\"", "繁體中文 😀")
            .set("metadatas.\"\"", "empty-key")
        val reparsed = FrpConfigDocument.parse(document.source)
        assertEquals(document.values, reparsed.values)
        assertEquals("empty-key", reparsed.get("metadatas.\"\""))
        assertNull(reparsed.get("metadatas.a.b"))
    }

    @Test
    fun allTomlTypesSurviveSerialization() {
        val document = FrpConfigDocument.parse(
            """
            date = 1979-05-27
            time = 07:32:00
            local = 1979-05-27T07:32:00
            offset = 1979-05-27T07:32:00-07:00
            integer = -9223372036854775808
            decimal = -0.0
            positive = +inf
            negative = -inf
            flag = true
            mixed = [[1, 2], { a = 3 }, "text"]
            emptyArray = []
            emptyTable = {}
            [[nested]]
            name = "first"
            [[nested.children]]
            name = "child"
            [nested.children.details]
            enabled = true
            [[nested]]
            name = "second"
            """.trimIndent()
        ).set("extra", "edited")
        assertEquals(document.values, FrpConfigDocument.parse(document.source).values)
        val nan = FrpConfigDocument.parse("value = nan").set("extra", true)
        assertTrue((FrpConfigDocument.parse(nan.source).get("value") as Double).isNaN())
    }

    @Test
    fun addingEditingAndRemovingRulesSupportsEveryProtocol() {
        var document = FrpConfigDocument.parse("")
        FrpConfigDocument.proxyTypes.forEach { document = document.addRule("proxies", it) }
        FrpConfigDocument.visitorTypes.forEach { document = document.addRule("visitors", it) }
        assertEquals(8, document.rules("proxies").size)
        assertEquals(3, document.rules("visitors").size)
        assertEquals(document.values, FrpConfigDocument.parse(document.source).values)
        val withDuplicateType = document.addRule("proxies", "tcp")
        assertNotEquals(document.rules("proxies")[0]["name"], withDuplicateType.rules("proxies").last()["name"])
        val edited = document.updateRule("proxies", 0, "transport.useEncryption", true)
        assertEquals(mapOf("useEncryption" to true), edited.rules("proxies")[0]["transport"])
        assertNull(edited.updateRule("proxies", 0, "transport.useEncryption", null).rules("proxies")[0]["transport"])
        repeat(3) { document = document.removeRule("visitors", 0) }
        assertTrue(document.rules("visitors").isEmpty())
        assertFalse(document.values.containsKey("visitors"))
    }

    @Test
    fun editingDefersSerializationAndSharesUntouchedRuleData() {
        val original = FrpConfigDocument.parse(
            """
            serverAddr = "example.com"
            [[proxies]]
            name = "first"
            type = "tcp"
            remotePort = 6000
            [proxies.plugin]
            type = "http_proxy"
            [[proxies]]
            name = "second"
            type = "udp"
            remotePort = 6001
            """.trimIndent()
        )
        var edited = original
        repeat(30) { edited = edited.updateRule("proxies", 0, "remotePort", 7000L + it) }
        assertFalse(original.isSourceMaterialized)
        assertFalse(edited.isSourceMaterialized)
        assertSame(original.rules("proxies")[1], edited.rules("proxies")[1])
        assertSame(original.rules("proxies")[0]["plugin"], edited.rules("proxies")[0]["plugin"])
        val source = edited.source
        assertTrue(edited.isSourceMaterialized)
        assertSame(source, edited.source)
        assertEquals(edited.values, FrpConfigDocument.parse(source).values)
    }

    @Test
    fun ruleIdentitiesSurviveRenameAndDeletionWithoutBeingSerialized() {
        val original = FrpConfigDocument.parse("").addRule("proxies", "tcp").addRule("proxies", "tcp")
        val first = original.ruleId("proxies", 0)
        val second = original.ruleId("proxies", 1)
        assertNotEquals(first, second)
        val renamed = original.updateRule("proxies", 1, "name", "renamed")
        assertEquals(second, renamed.ruleId("proxies", 1))
        val removed = renamed.removeRule("proxies", 0)
        assertEquals(second, removed.ruleId("proxies", 0))
        val added = removed.addRule("proxies", "tcp")
        assertEquals(second, added.ruleId("proxies", 0))
        assertNotEquals(first, added.ruleId("proxies", 1))
        assertNotEquals(second, added.ruleId("proxies", 1))
        assertEquals(
            setOf("name", "type", "localIP", "localPort", "remotePort"),
            FrpConfigDocument.parse(added.source).rules("proxies")[0].keys
        )
    }

    @Test
    fun invalidTomlDoesNotExposeInputInDiagnostics() {
        val exception = try {
            FrpConfigDocument.parse("auth.token = \"sensitive-value\" trailing-sensitive-value")
            throw AssertionError("Expected invalid TOML to fail.")
        } catch (exception: IllegalArgumentException) {
            exception
        }
        assertEquals("Invalid TOML configuration.", exception.message)
        assertNull(exception.cause)
    }

    @Test
    fun conflictingPathsNeverDiscardExistingValues() {
        val original = FrpConfigDocument.parse("transport = \"unknown-future-value\"")
        try {
            original.set("transport.protocol", "tcp")
            throw AssertionError("Expected conflicting path to fail.")
        } catch (exception: IllegalArgumentException) {
            assertEquals("Configuration key conflicts with an existing value.", exception.message)
        }
        assertEquals("unknown-future-value", original.get("transport"))
    }
}
