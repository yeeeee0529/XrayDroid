package io.github.xraydroid.runtime

import android.content.Intent
import android.os.SystemClock
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xraydroid.MainActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkSwitchLifecycleTest {
    @Test
    fun selectionRestartsCoreAndUnavailableNetworkStopsItUntilRecovery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".validation")) {
            "Lifecycle verification requires an isolated validation application ID"
        }
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        instrumentation.runOnMainSync { NetworkStore.initialize(context) }
        val state = NetworkStore.state.value
        assumeTrue("Wi-Fi is required for network switching verification", state.options.any { it.mode == OutboundNetworkMode.WIFI })
        assumeTrue(
            "Unavailable Ethernet is required for fail-closed verification",
            state.options.none { it.mode == OutboundNetworkMode.ETHERNET }
        )
        val panelPath = "${context.applicationInfo.nativeLibraryDir}/libxui.so"
        val xrayPath = "${context.applicationInfo.nativeLibraryDir}/libxray.so"
        fun select(mode: OutboundNetworkMode) = instrumentation.runOnMainSync { NetworkStore.select(mode) }

        try {
            select(OutboundNetworkMode.WIFI)
            XuiService.dispatch(context, XuiService.ACTION_START)
            awaitCondition("Service must run using available Wi-Fi") {
                ServerStore.state.value.phase == ServerPhase.RUNNING &&
                    ownedPid(panelPath) != null && ownedPid(xrayPath) != null
            }
            val wifiPanel = requireNotNull(ownedPid(panelPath))
            val wifiCore = requireNotNull(ownedPid(xrayPath))
            val wifiLabel = requireNotNull(ServerStore.state.value.outboundNetworkLabel)
            val wifiInterface = requireNotNull(NetworkStore.state.value.selectedOption).interfaceName
            assertTrue("Applied network label must identify selected Wi-Fi interface", wifiLabel.contains(wifiInterface))

            select(OutboundNetworkMode.SYSTEM)
            awaitCondition("Selecting the system network must restart the panel and core") {
                ServerStore.state.value.phase == ServerPhase.RUNNING &&
                    ownedPid(panelPath)?.let { it != wifiPanel } == true &&
                    ownedPid(xrayPath)?.let { it != wifiCore } == true
            }
            val systemPanel = requireNotNull(ownedPid(panelPath))
            assertTrue(
                "Applied system network label must differ from Wi-Fi selection",
                ServerStore.state.value.outboundNetworkLabel != wifiLabel
            )

            select(OutboundNetworkMode.ETHERNET)
            awaitCondition("Unavailable selected network must stop all owned server processes") {
                ServerStore.state.value.phase == ServerPhase.WAITING_FOR_NETWORK &&
                    ownedPid(panelPath) == null && ownedPid(xrayPath) == null
            }
            select(OutboundNetworkMode.SYSTEM)
            awaitCondition("Returning to an available network must recover the requested service") {
                ServerStore.state.value.phase == ServerPhase.RUNNING &&
                    ownedPid(panelPath)?.let { it != systemPanel } == true &&
                    ownedPid(xrayPath) != null
            }

            XuiService.dispatch(context, XuiService.ACTION_STOP)
            awaitCondition("Stop must release all owned processes") {
                ServerStore.state.value.phase == ServerPhase.STOPPED &&
                    ownedPid(panelPath) == null && ownedPid(xrayPath) == null
            }
            select(OutboundNetworkMode.WIFI)
            SystemClock.sleep(2000)
            assertEquals("Changing selection after Stop must not restart the service", ServerPhase.STOPPED, ServerStore.state.value.phase)
            assertTrue("Stopped service must not recreate the core", ownedPid(xrayPath) == null)
            // 快速網路事件不得使最後一個停止指令失效。
            repeat(5) {
                XuiService.dispatch(context, XuiService.ACTION_START)
                select(OutboundNetworkMode.ETHERNET)
                XuiService.dispatch(context, XuiService.ACTION_STOP)
                select(OutboundNetworkMode.SYSTEM)
            }
            XuiService.dispatch(context, XuiService.ACTION_STOP)
            awaitCondition("Final Stop must win over concurrent network changes") {
                ServerStore.state.value.phase == ServerPhase.STOPPED &&
                    ownedPid(panelPath) == null && ownedPid(xrayPath) == null
            }
            SystemClock.sleep(2000)
            assertEquals(ServerPhase.STOPPED, ServerStore.state.value.phase)
        } finally {
            XuiService.dispatch(context, XuiService.ACTION_STOP)
            awaitCondition("Final cleanup must stop the validation service") {
                ServerStore.state.value.phase == ServerPhase.STOPPED &&
                    ownedPid(panelPath) == null && ownedPid(xrayPath) == null
            }
            select(OutboundNetworkMode.SYSTEM)
        }
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError(message)
    }

    private fun ownedPid(path: String): Int? = File("/proc").listFiles()?.firstNotNullOfOrNull { directory ->
        val pid = directory.name.toIntOrNull() ?: return@firstNotNullOfOrNull null
        runCatching {
            if (Os.stat(directory.path).st_uid == android.os.Process.myUid() &&
                Os.readlink(File(directory, "exe").path) == path
            ) {
                pid
            } else {
                null
            }
        }.getOrNull()
    }
}
