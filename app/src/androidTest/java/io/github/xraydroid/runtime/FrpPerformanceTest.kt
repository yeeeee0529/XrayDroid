package io.github.xraydroid.runtime

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
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
class FrpPerformanceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun syntheticDocumentPerformance() {
        for (ruleCount in listOf(10, 100, 500)) {
            val config = syntheticConfig(ruleCount)
            repeat(2) {
                FrpConfigDocument.parse(config)
                editDocument(FrpConfigDocument.parse(config))
            }
            val parseSamples = List(7) {
                timed { assertEquals(ruleCount, FrpConfigDocument.parse(config).rules("proxies").size) }
            }
            val editSamples = mutableListOf<Long>()
            val exportSamples = mutableListOf<Long>()
            repeat(7) {
                val original = FrpConfigDocument.parse(config)
                lateinit var edited: FrpConfigDocument
                editSamples += timed { edited = editDocument(original) }
                lateinit var exported: String
                exportSamples += timed { exported = edited.source }
                val reparsed = FrpConfigDocument.parse(exported)
                assertEquals(8029L, reparsed.rules("proxies").first()["localPort"])
                assertEquals(ruleCount, reparsed.rules("proxies").size)
                assertEquals("preserved", reparsed.get("metadatas.fixture"))
            }
            Log.i(
                "XrayDroidPerf",
                "synthetic rules=$ruleCount bytes=${config.length} samples=7 " +
                    "parseMedianNs=${median(parseSamples)} " +
                    "edit30MedianNs=${median(editSamples)} exportMedianNs=${median(exportSamples)}"
            )
        }
    }

    @Test
    fun manyRulesKeepInvalidDraftWhenScrolledOffscreen() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "io.github.xraydroid.validation") {
            "FRP form verification requires an isolated validation application ID"
        }
        compose.setContent {
            MaterialTheme {
                FrpScreen(FrpState(config = syntheticConfig(100), loaded = true), {}, {}, {}, {})
            }
        }
        scrollToText("▸ fixture-0 · TCP").performClick()
        scrollToText("本機服務連接埠")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        instrumentation.uiAutomation.executeShellCommand("dumpsys gfxinfo $packageName reset").close()
        val editSamples = List(7) { index ->
            timed {
                compose.onNodeWithText("本機服務連接埠").performTextReplacement((9000 + index).toString())
                compose.waitForIdle()
            }
        }
        Log.i("XrayDroidPerf", "synthetic ui rules=100 samples=7 editIdleMedianNs=${median(editSamples)}")
        val frames = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("dumpsys gfxinfo $packageName framestats")
        ).bufferedReader().use { it.readText() }
        frames.lineSequence().filter {
            it.startsWith("Total frames rendered:") || it.startsWith("Janky frames:") || it.contains("percentile:")
        }.forEach { Log.i("XrayDroidPerf", "synthetic ui frames $it") }
        compose.onNodeWithText("本機服務連接埠").performTextReplacement("invalid")
        scrollToText("▸ fixture-99 · TCP")
        scrollToText("TOML 配置").assertIsNotEnabled()
        scrollToText("儲存設定").assertIsNotEnabled()
        scrollToText("本機服務連接埠")
        assertEquals(
            "invalid",
            compose.onNodeWithText("本機服務連接埠").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        )
        compose.onNodeWithText("本機服務連接埠").performTextReplacement("9123")
        scrollToText("TOML 配置").performClick()
        val exported = scrollToText("frpc.toml").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        val document = FrpConfigDocument.parse(exported)
        assertEquals(9123L, document.rules("proxies").first()["localPort"])
        assertEquals(100, document.rules("proxies").size)
        assertEquals("preserved", document.get("metadatas.fixture"))
    }

    private fun scrollToText(text: String): SemanticsNodeInteraction {
        compose.waitUntil(30_000) {
            runCatching {
                compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
                compose.onNodeWithText(text).performScrollTo()
            }.isSuccess
        }
        return compose.onNodeWithText(text)
    }

    private fun editDocument(original: FrpConfigDocument): FrpConfigDocument {
        var document = original
        repeat(30) { document = document.updateRule("proxies", 0, "localPort", 8000L + it) }
        return document
    }

    private fun timed(block: () -> Unit): Long {
        val start = SystemClock.elapsedRealtimeNanos()
        block()
        return SystemClock.elapsedRealtimeNanos() - start
    }

    private fun median(samples: List<Long>): Long = samples.sorted()[samples.size / 2]

    private fun syntheticConfig(ruleCount: Int): String = buildString {
        append("# Synthetic performance fixture\nserverAddr = '127.0.0.1'\nserverPort = 7000\n")
        append("[metadatas]\nfixture = 'preserved'\n")
        repeat(ruleCount) { index ->
            append("\n[[proxies]]\nname = 'fixture-$index'\ntype = 'tcp'\n")
            append("localIP = '127.0.0.1'\nlocalPort = 8080\nremotePort = ${20000 + index}\n")
        }
    }
}
