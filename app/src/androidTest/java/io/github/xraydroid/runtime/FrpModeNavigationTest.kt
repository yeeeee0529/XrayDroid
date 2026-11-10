package io.github.xraydroid.runtime

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xraydroid.ui.FrpScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrpModeNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun switchingModesWithoutEditingKeepsOriginalToml() {
        val original = "# Keep this comment\nserverAddr = '127.0.0.1'\nserverPort = 7000\n"
        showScreen(original)
        clickText("TOML 配置")
        assertEquals(original, editorText())
        clickText("表單配置")
        clickText("TOML 配置")
        assertEquals(original, editorText())
    }

    @Test
    fun invalidTomlKeepsEditorAndCanBeCorrected() {
        showScreen("serverAddr = '127.0.0.1'\n")
        clickText("TOML 配置")
        scrollToText("frpc.toml").performTextReplacement("serverPort = [")
        clickText("表單配置")
        // 操作回饋顯示在浮動工具列上方，不在捲動清單內。
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("無法切換至表單，請檢查 TOML 語法與欄位型別；原有草稿已保留。").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("無法切換至表單，請檢查 TOML 語法與欄位型別；原有草稿已保留。").assertIsDisplayed()
        assertEquals("serverPort = [", editorText())
        scrollToText("frpc.toml").performTextReplacement("serverAddr = 'localhost'\nserverPort = 7000\n")
        clickText("表單配置")
        clickText("TOML 配置")
        assertEquals("serverAddr = 'localhost'\nserverPort = 7000\n", editorText())
    }

    @Test
    fun formEditsPreserveAdvancedOptionsAndInvalidNumbersUntilCorrected() {
        showScreen(
            """
            serverAddr = 'localhost'
            serverPort = 7000
            includes = ['conf.d/*.toml']
            [auth.tokenSource]
            type = 'file'
            file.path = 'support/fixture.txt'
            """.trimIndent()
        )
        clickText("基本設定")
        scrollToText("伺服器位址").performTextReplacement("edited.example.invalid")
        scrollToText("伺服器連接埠").performTextReplacement("invalid")
        scrollToText("TOML 配置").assertIsNotEnabled()
        compose.onNodeWithText("儲存設定").assertIsNotEnabled()
        // 分節以展開／收合語意動作表達狀態；仍收合時先展開。
        if (SemanticsActions.Expand in scrollToText("基本設定").fetchSemanticsNode().config) {
            compose.onNodeWithText("基本設定").click()
        }
        scrollToText("伺服器連接埠")
        assertEquals("invalid", compose.onNodeWithText("伺服器連接埠").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithText("伺服器連接埠").performTextReplacement("7001")
        clickText("TOML 配置")
        val parsed = FrpConfigDocument.parse(editorText())
        assertEquals("edited.example.invalid", parsed.get("serverAddr"))
        assertEquals(7001L, parsed.get("serverPort"))
        assertEquals(listOf("conf.d/*.toml"), parsed.get("includes"))
        assertEquals("support/fixture.txt", parsed.get("auth.tokenSource.file.path"))
    }

    @Test
    fun incompatibleTableStructuresStayInTomlMode() {
        val original = "transport = 'conflicting-table-value'\n"
        showScreen(original)
        clickText("表單配置")
        assertEquals(original, editorText())
    }

    @Test
    fun everyProtocolCanBeAddedFromTheFormAndRemovedWithoutLosingOtherRules() {
        showScreen("serverAddr = 'localhost'\nserverPort = 7000\n")
        var selected = "tcp"
        val proxies = listOf("tcp", "udp", "http", "https", "stcp", "sudp", "xtcp", "tcpmux")
        proxies.forEach { type ->
            if (type != selected) {
                compose.onAllNodesWithText(selected)[0].performScrollTo().click()
                compose.onNode(hasText(type) and hasAnyAncestor(isPopup())).performClick()
                selected = type
            }
            clickText("新增轉發規則")
        }
        selected = "stcp"
        val visitors = listOf("stcp", "sudp", "xtcp")
        visitors.forEach { type ->
            if (type != selected) {
                compose.onNodeWithText(selected).performScrollTo().click()
                compose.onNode(hasText(type) and hasAnyAncestor(isPopup())).performClick()
                selected = type
            }
            clickText("新增訪客規則")
        }
        clickText("http-1 · HTTP")
        scrollToText("自訂網域").performTextReplacement("first.example.invalid\nsecond.example.invalid")
        clickText("TOML 配置")
        val generated = FrpConfigDocument.parse(editorText())
        assertEquals(proxies, generated.rules("proxies").map { it["type"] })
        assertEquals(visitors, generated.rules("visitors").map { it["type"] })
        assertEquals(listOf("first.example.invalid", "second.example.invalid"), generated.rules("proxies")[2]["customDomains"])
        clickText("表單配置")
        clickText("udp-1 · UDP")
        clickText("刪除此規則")
        clickText("TOML 配置")
        val removed = FrpConfigDocument.parse(editorText())
        assertEquals(proxies.filter { it != "udp" }, removed.rules("proxies").map { it["type"] })
        assertEquals(visitors, removed.rules("visitors").map { it["type"] })
    }

    private fun clickText(text: String) {
        compose.waitUntil(30_000) {
            runCatching { isEnabled().matches(scrollToText(text).fetchSemanticsNode()) }.getOrDefault(false)
        }
        compose.onNodeWithText(text).click()
    }

    // 清單內容會捲到浮動工具列之下；以語意動作點按，不受疊在上方的控制項遮擋。
    private fun SemanticsNodeInteraction.click() = performSemanticsAction(SemanticsActions.OnClick)

    private fun scrollToText(text: String, substring: Boolean = false): SemanticsNodeInteraction {
        compose.waitUntil(30_000) {
            runCatching {
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = substring))
                compose.onNodeWithText(text, substring = substring).performScrollTo()
            }.isSuccess
        }
        return compose.onNodeWithText(text, substring = substring)
    }

    private fun showScreen(config: String) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "io.github.xraydroid.validation") {
            "FRP form verification requires an isolated validation application ID"
        }
        compose.setContent {
            MaterialTheme {
                FrpScreen(FrpState(config = config, loaded = true), {}, {}, {}, {})
            }
        }
        compose.waitUntil(30_000) {
            runCatching {
                !scrollToText("表單配置").fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
            }.getOrDefault(false)
        }
    }

    private fun editorText(): String = scrollToText("frpc.toml")
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
}
