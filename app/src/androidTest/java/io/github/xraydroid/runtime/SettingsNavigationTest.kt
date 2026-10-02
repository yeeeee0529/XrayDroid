package io.github.xraydroid.runtime

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xraydroid.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
    @Test
    fun settingsKeepsNetworkAndFrpSeparateAndReturnsToHome() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".validation")) {
            "Navigation verification requires an isolated validation application ID"
        }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        assumeTrue("Development device must be unlocked for navigation verification", !keyguard.isKeyguardLocked)
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        awaitText("XrayDroid")
        clickText("設定")
        awaitText("出站網路")
        awaitText("frp")
        assertTrue("Network controls must remain inside the network subpage", matchingNodes("重新偵測").isEmpty())
        clickText("出站網路")
        awaitText("重新偵測")
        // 已選取的單選項可能不提供無障礙點擊動作；先建立指定介面，再由 UI 切回系統。
        instrumentation.runOnMainSync { NetworkStore.selectInterface("xraydroid-navigation-unavailable") }
        clickText("跟隨系統")
        assertEquals(OutboundNetworkMode.SYSTEM, NetworkStore.state.value.selectedMode)
        assertTrue("System selection must clear an explicitly selected interface", NetworkStore.state.value.selectedInterfaceName == null)
        clickText("重新偵測")
        awaitText("出站網路")
        clickText("返回設定")
        awaitText("frp")
        assertTrue("Network controls must disappear after returning to settings", matchingNodes("重新偵測").isEmpty())
        clickText("frp")
        awaitText("frpc 用戶端")
        awaitText("配置模式")
        assertTrue("frp must not display outbound network controls", matchingNodes("跟隨系統").isEmpty())
        assertTrue(
            "System Back action must return from frp to settings",
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        )
        awaitText("出站網路")
        assertTrue("frpc editor must disappear after leaving its subpage", matchingNodes("配置模式").isEmpty())
        clickText("返回首頁")
        awaitText("XrayDroid")
        awaitText("管理面板")
        // 再次進入設定，確認返回首頁後仍保留網路選擇。
        clickText("設定")
        awaitText("出站網路")
        assertEquals(OutboundNetworkMode.SYSTEM, NetworkStore.state.value.selectedMode)
        assertTrue(
            "System Back action must be accepted",
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        )
        awaitText("XrayDroid")
    }

    private fun awaitText(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (matchingNodes(text).isNotEmpty()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Expected visible navigation label: $text")
    }

    private fun clickText(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            matchingNodes(text).forEach { match ->
                var current: AccessibilityNodeInfo? = match
                while (current != null) {
                    if (current.isEnabled && current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                        return
                    }
                    current = current.parent
                }
            }
            SystemClock.sleep(100)
        }
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        val details = mutableListOf<String>()
        fun describe(node: AccessibilityNodeInfo) {
            val label = node.text?.toString().orEmpty()
            if (label.contains(text) || node.contentDescription?.toString()?.contains(text) == true) {
                details += "class=${node.className}, enabled=${node.isEnabled}, clickable=${node.isClickable}, " +
                    "visible=${node.isVisibleToUser}, prefix=${label.take(text.length + 4)}, " +
                    "actions=${node.actionList.map { it.id }}"
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(::describe) }
        }
        root?.let(::describe)
        throw AssertionError("Expected clickable navigation label: $text; phase=${ServerStore.state.value.phase}; candidates=$details")
    }

    private fun matchingNodes(text: String): List<AccessibilityNodeInfo> {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return emptyList()
        // Activity 啟動是非同步的；正式版也可能顯示相同標題，不可點到前一個視窗。
        if (root.packageName?.toString() != InstrumentationRegistry.getInstrumentation().targetContext.packageName) return emptyList()
        val matches = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            // Compose 可將可選列的標題與說明合併；與無障礙文字搜尋 API 一樣以包含標籤比對。
            val hasLabel = node.text?.toString()?.contains(text) == true
            if (node.isVisibleToUser && (hasLabel || node.contentDescription?.toString() == text)) {
                matches += node
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(::visit) }
        }
        visit(root)
        return matches
    }
}
