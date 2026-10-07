package io.github.xraydroid.runtime

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xraydroid.MainActivity
import io.github.xraydroid.R
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
        awaitText(context.getString(R.string.frp_instance_default_name))
        assertTrue("Network controls must remain inside the instance editor", matchingNodes("重新偵測").isEmpty())
        clickText(context.getString(R.string.frp_instance_default_name))
        awaitText(context.getString(R.string.frp_instances_back))
        scrollToText(context.getString(R.string.frp_network_title))
        instrumentation.runOnMainSync { FrpNetworkStore.selectInterface("frp-navigation-unavailable") }
        scrollToText("跟隨系統")
        clickText("跟隨系統")
        assertEquals(OutboundNetworkMode.SYSTEM, FrpNetworkStore.state.value.selectedMode)
        assertTrue("FRP system selection must clear its explicit interface", FrpNetworkStore.state.value.selectedInterfaceName == null)
        assertEquals("FRP controls must preserve Xray selection", OutboundNetworkMode.SYSTEM, NetworkStore.state.value.selectedMode)
        assertTrue(NetworkStore.state.value.selectedInterfaceName == null)
        scrollToText("配置模式")
        assertTrue(
            "System Back action must return from the editor to the instance list",
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        )
        awaitText(context.getString(R.string.frp_instance_default_name))
        // 返回轉場期間上一個畫面仍在階層中，需等待其實際消失。
        awaitGone("配置模式")
        assertTrue(
            "System Back action must return from the instance list to settings",
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        )
        awaitText("出站網路")
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

    private fun scrollToText(text: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            repeat(20) {
                if (matchingNodes(text).isNotEmpty()) return
                val root = instrumentation.uiAutomation.rootInActiveWindow
                fun scrollBounds(node: AccessibilityNodeInfo): Rect? {
                    if (!node.isVisibleToUser) return null
                    if (node.isScrollable) return Rect().also(node::getBoundsInScreen)
                    repeat(node.childCount) { index ->
                        node.getChild(index)?.let(::scrollBounds)?.let { return it }
                    }
                    return null
                }
                if (root?.packageName?.toString() == instrumentation.targetContext.packageName) {
                    root?.let(::scrollBounds)?.let { bounds ->
                        val x = bounds.exactCenterX()
                        val top = bounds.top + bounds.height() * 0.25f
                        val bottom = bounds.bottom - bounds.height() * 0.25f
                        val start = if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) bottom else top
                        val end = if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) top else bottom
                        val downTime = SystemClock.uptimeMillis()
                        fun event(kind: Int, time: Long, y: Float) {
                            val input = MotionEvent.obtain(downTime, time, kind, x, y, 0).apply {
                                source = InputDevice.SOURCE_TOUCHSCREEN
                            }
                            try {
                                assertTrue("Navigation swipe must be accepted", instrumentation.uiAutomation.injectInputEvent(input, true))
                            } finally {
                                input.recycle()
                            }
                        }
                        event(MotionEvent.ACTION_DOWN, downTime, start)
                        for (step in 1..8) {
                            SystemClock.sleep(30)
                            event(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis(), start + (end - start) * step / 8)
                        }
                        event(MotionEvent.ACTION_UP, SystemClock.uptimeMillis(), end)
                    }
                }
                // 放開後的慣性滑動會快速掃過目標；動畫期間必須持續偵測，否則會整段跳過。
                repeat(40) {
                    if (matchingNodes(text).isNotEmpty()) return
                    SystemClock.sleep(16)
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(100)
            }
        }
        throw AssertionError("Expected scrollable navigation label: $text")
    }

    private fun awaitGone(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 2000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (matchingNodes(text).isEmpty()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Expected navigation label to disappear: $text")
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
