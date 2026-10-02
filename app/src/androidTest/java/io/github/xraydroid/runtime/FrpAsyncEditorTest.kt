package io.github.xraydroid.runtime

import android.os.SystemClock
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xraydroid.ui.FrpScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrpAsyncEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun asynchronousModeSwitchesPreserveOriginalAndAdvancedValues() {
        val original = "# Synthetic comment\nserverAddr = 'localhost'\nserverPort = 7000\nincludes = ['conf.d/*.toml']\n"
        showScreen(original)
        enabledText("表單配置")
        enabledText("TOML 配置").performClick()
        assertEquals(original, editorText())
        enabledText("表單配置").performClick()
        enabledText("▸ 基本設定").performClick()
        enabledText("伺服器位址").performTextReplacement("edited.example.invalid")
        enabledText("TOML 配置").performClick()
        val document = FrpConfigDocument.parse(editorText())
        assertEquals("edited.example.invalid", document.get("serverAddr"))
        assertEquals(listOf("conf.d/*.toml"), document.get("includes"))
    }

    @Test
    fun initialLoadingUsesLatestConfigurationWhenLoadedArrives() {
        assertValidationPackage()
        val state = mutableStateOf(FrpState(config = "serverAddr = 'old.example.invalid'\n", loaded = false))
        compose.setContent {
            MaterialTheme { FrpScreen(state.value, {}, {}, {}, {}) }
        }
        val latest = "# Latest synthetic fixture\nserverAddr = 'latest.example.invalid'\n"
        compose.runOnIdle { state.value = FrpState(config = latest, loaded = true) }
        enabledText("表單配置")
        enabledText("TOML 配置").performClick()
        assertEquals(latest, editorText())
    }

    @Test
    fun nearLimitConfigurationLoadsAndFormRemainsEditable() {
        val config = "serverAddr = 'localhost'\n[metadatas]\nfixture = '${"x".repeat(1000 * 1024)}'\n"
        val start = SystemClock.elapsedRealtimeNanos()
        showScreen(config)
        enabledText("表單配置")
        enabledText("▸ 基本設定").performClick()
        enabledText("伺服器位址").performTextReplacement("large.example.invalid")
        Log.i("XrayDroidPerf", "synthetic nearLimit bytes=${config.length} loadAndEditNs=${SystemClock.elapsedRealtimeNanos() - start}")
        scrollToText("草稿尚未儲存。").assertIsDisplayed()
    }

    @Test
    fun dirtyFormRequiresConfirmationBeforeDisposal() {
        assertValidationPackage()
        val visible = mutableStateOf(true)
        var departures = 0
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    FrpScreen(
                        FrpState(config = "serverAddr = 'localhost'\n", loaded = true),
                        {
                            departures++
                            visible.value = false
                        },
                        {},
                        {},
                        {}
                    )
                }
            }
        }
        enabledText("表單配置")
        enabledText("▸ 基本設定").performClick()
        enabledText("伺服器位址").performTextReplacement("unsaved.example.invalid")
        scrollToText("返回設定").performClick()
        compose.onNodeWithText("捨棄未儲存的草稿？").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, departures) }
        compose.onNodeWithText("繼續編輯").performClick()
        scrollToText("返回設定").performClick()
        compose.onNodeWithText("捨棄並返回").performClick()
        compose.runOnIdle {
            assertEquals(1, departures)
            assertEquals(false, visible.value)
        }
    }

    private fun showScreen(config: String) {
        assertValidationPackage()
        compose.setContent {
            MaterialTheme { FrpScreen(FrpState(config = config, loaded = true), {}, {}, {}, {}) }
        }
    }

    private fun assertValidationPackage() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "io.github.xraydroid.validation") {
            "FRP async verification requires an isolated validation application ID"
        }
    }

    private fun enabledText(text: String): SemanticsNodeInteraction {
        compose.waitUntil(30_000) {
            runCatching { scrollToText(text).fetchSemanticsNode().let { isEnabled().matches(it) } }.getOrDefault(false)
        }
        return compose.onNodeWithText(text)
    }

    private fun scrollToText(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text).performScrollTo()
    }

    private fun editorText(): String = enabledText("frpc.toml")
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
}
