package io.github.xraydroid.runtime

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xraydroid.MainActivity
import io.github.xraydroid.R
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrpNetworkSelectionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val executable get() = "${context.applicationInfo.nativeLibraryDir}/libfrpc.so"
    private lateinit var originalFrpSelection: NetworkState
    private lateinit var originalXraySelection: NetworkState
    private var originalConfig: String? = null
    private var initialized = false

    @Before
    fun prepareIsolatedApplication() {
        check(context.packageName == "io.github.xraydroid.validation") {
            "FRP network verification requires the isolated validation application ID"
        }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        onMain {
            NetworkStore.initialize(context)
            FrpNetworkStore.initialize(context)
        }
        originalFrpSelection = FrpNetworkStore.state.value
        originalXraySelection = NetworkStore.state.value
        initialized = true
        stopClient()
        onMain {
            NetworkStore.select(OutboundNetworkMode.SYSTEM)
            FrpNetworkStore.select(OutboundNetworkMode.SYSTEM)
            NetworkStore.refreshInterfaces()
            FrpNetworkStore.refreshInterfaces()
        }
        FrpStore.initialize(context)
        await("FRP settings must finish loading") { FrpStore.state.value.loaded }
    }

    @After
    fun restoreIsolatedApplication() = runBlocking(Dispatchers.IO) {
        if (!initialized) return@runBlocking
        try {
            stopClient()
        } finally {
            try {
                originalConfig?.takeIf { it.isNotBlank() }?.let {
                    assertTrue("Original validation configuration must be restored", FrpStore.saveConfig(context, it))
                }
            } finally {
                onMain {
                    FrpNetworkStore.refreshInterfaces()
                    NetworkStore.refreshInterfaces()
                    restoreSelection(FrpNetworkStore, originalFrpSelection)
                    restoreSelection(NetworkStore, originalXraySelection)
                }
            }
        }
    }

    @Test
    fun selectionsPersistIndependentlyWithoutSavingNetworkHandles() {
        val frpPreferences = context.getSharedPreferences("frp_network", Context.MODE_PRIVATE)
        val xrayPreferences = context.getSharedPreferences("outbound_network", Context.MODE_PRIVATE)
        val missingName = missingInterfaceName()
        onMain {
            NetworkStore.select(OutboundNetworkMode.VPN)
            FrpNetworkStore.select(OutboundNetworkMode.OTHER)
            FrpNetworkStore.selectInterface(missingName)
        }
        assertEquals(OutboundNetworkMode.VPN, NetworkStore.state.value.selectedMode)
        assertNull(NetworkStore.state.value.selectedInterfaceName)
        assertEquals(mapOf("mode" to "OTHER", "interface_name" to missingName), frpPreferences.all)
        assertEquals(mapOf("mode" to "VPN"), xrayPreferences.all)

        // 新實例必須只由偏好設定恢復選擇，不沿用原物件的網路 handle。
        val restored = NetworkSelectionStore("frp_network")
        onMain { restored.initialize(context) }
        assertEquals(OutboundNetworkMode.OTHER, restored.state.value.selectedMode)
        assertEquals(missingName, restored.state.value.selectedInterfaceName)
        assertNull("A persisted missing interface must never fall back", restored.state.value.selectedOption)
        assertEquals(
            R.string.network_reason_missing,
            restored.state.value.interfaces.single { it.interfaceName == missingName }.unavailableReason
        )
        onMain { FrpNetworkStore.refreshInterfaces() }
        assertEquals("Refresh must retain the unavailable interface name", missingName, FrpNetworkStore.state.value.selectedInterfaceName)
        onMain { FrpNetworkStore.select(OutboundNetworkMode.SYSTEM) }
        assertEquals(mapOf("mode" to "SYSTEM"), frpPreferences.all)
        assertEquals("Changing FRP must preserve Xray preferences", mapOf("mode" to "VPN"), xrayPreferences.all)
    }

    @Test
    fun nativeVersionProbeRejectsInvalidHandle() {
        assertEquals("System routing must support the native version command", 0, versionProbe(0L))
        assertTrue("Invalid network handle must reject startup before version output", versionProbe(Long.MAX_VALUE) != 0)
    }

    @Test
    fun nativeVersionProbeAcceptsAvailableWifi() {
        val wifi = availableWifi()
        assertEquals("Available Wi-Fi binding must succeed, including under the application UID", 0, versionProbe(wifi.handle))
    }

    @Test
    fun availableWifiPersistsOnlyModeAndNameAndRestoresCurrentHandle() {
        val wifi = availableWifi()
        onMain { FrpNetworkStore.selectInterface(wifi.interfaceName) }
        val preferences = context.getSharedPreferences("frp_network", Context.MODE_PRIVATE)
        assertEquals(mapOf("mode" to "WIFI", "interface_name" to wifi.interfaceName), preferences.all)
        assertEquals(
            "FRP interface selection must preserve Xray preferences",
            OutboundNetworkMode.SYSTEM,
            NetworkStore.state.value.selectedMode
        )
        assertNull(NetworkStore.state.value.selectedInterfaceName)
        val restored = NetworkSelectionStore("frp_network")
        onMain { restored.initialize(context) }
        assertEquals(wifi.interfaceName, restored.state.value.selectedInterfaceName)
        assertEquals(OutboundNetworkMode.WIFI, restored.state.value.selectedMode)
        await("Restored selection must resolve the currently available Android network") {
            restored.state.value.selectedOption?.handle == wifi.handle
        }
        assertEquals("Resolving a runtime handle must not persist it", setOf("mode", "interface_name"), preferences.all.keys)
    }

    @Test
    fun frpBindingFailureIsIsolatedAndRefreshRecoversRequestedClient() = runBlocking(Dispatchers.IO) {
        val wifi = availableWifi()
        assertEquals("Wi-Fi binding must work before testing failure recovery", 0, versionProbe(wifi.handle))
        saveLocalConfig()
        onMain {
            NetworkStore.selectInterface(wifi.interfaceName)
            FrpNetworkStore.selectInterface(wifi.interfaceName)
        }
        await("Both stores must independently resolve Wi-Fi") {
            NetworkStore.state.value.selectedOption?.handle == wifi.handle &&
                FrpNetworkStore.state.value.selectedOption?.handle == wifi.handle
        }
        FrpService.dispatch(context, FrpService.ACTION_START)
        awaitRunning(wifi.interfaceName)
        val originalGroup = requireNotNull(OwnedProcesses.groupFor(executable))
        val xraySelection = NetworkStore.state.value
        val serverPhase = ServerStore.state.value.phase
        onMain { FrpNetworkStore.reportBindingFailure(wifi.interfaceName, wifi.handle) }
        awaitWaiting()
        assertEquals(wifi.interfaceName, FrpNetworkStore.state.value.selectedInterfaceName)
        assertEquals(
            R.string.network_reason_binding_failed,
            FrpNetworkStore.state.value.interfaces.single { it.interfaceName == wifi.interfaceName }.unavailableReason
        )
        assertEquals(
            "FRP failure must preserve Xray selection",
            xraySelection.selectedInterfaceName,
            NetworkStore.state.value.selectedInterfaceName
        )
        assertEquals(xraySelection.selectedMode, NetworkStore.state.value.selectedMode)
        assertEquals("FRP failure must not invalidate Xray Wi-Fi", wifi.handle, NetworkStore.state.value.selectedOption?.handle)
        assertNull(NetworkStore.state.value.interfaces.single { it.interfaceName == wifi.interfaceName }.unavailableReason)
        assertEquals("FRP failure must not transition the Xray service", serverPhase, ServerStore.state.value.phase)
        onMain { FrpNetworkStore.refreshInterfaces() }
        awaitRunning(wifi.interfaceName)
        assertTrue("Refresh must replace the stopped native client", OwnedProcesses.groupFor(executable) != originalGroup)
    }

    @Test
    fun missingInterfaceWaitsRecoversAndStopWinsWithoutXrayRestartingFrpc() = runBlocking(Dispatchers.IO) {
        val wifi = availableWifi()
        assertEquals("Wi-Fi binding is required for lifecycle verification", 0, versionProbe(wifi.handle))
        saveLocalConfig()
        onMain { FrpNetworkStore.selectInterface(wifi.interfaceName) }
        FrpService.dispatch(context, FrpService.ACTION_START)
        awaitRunning(wifi.interfaceName)
        val originalGroup = requireNotNull(OwnedProcesses.groupFor(executable))
        val missingName = missingInterfaceName()
        onMain { NetworkStore.selectInterface(missingName) }
        assertStable("Xray selection must not restart or stop frpc") {
            FrpStore.state.value.phase == FrpPhase.RUNNING && OwnedProcesses.groupFor(executable) == originalGroup
        }
        onMain { NetworkStore.select(OutboundNetworkMode.SYSTEM) }
        assertStable("Restoring Xray system routing must not restart frpc") {
            FrpStore.state.value.phase == FrpPhase.RUNNING && OwnedProcesses.groupFor(executable) == originalGroup
        }
        assertEquals(wifi.interfaceName, FrpNetworkStore.state.value.selectedInterfaceName)

        onMain { FrpNetworkStore.selectInterface(missingName) }
        awaitWaiting()
        assertEquals("Unavailable explicit selection must be retained", missingName, FrpNetworkStore.state.value.selectedInterfaceName)
        assertNull(FrpNetworkStore.state.value.selectedOption)
        assertStable("Missing interface must keep waiting without system fallback") {
            FrpStore.state.value.phase == FrpPhase.WAITING_FOR_NETWORK && !OwnedProcesses.isRunning(executable)
        }
        onMain { FrpNetworkStore.selectInterface(wifi.interfaceName) }
        awaitRunning(wifi.interfaceName)
        assertTrue("Recovery must create a new native client", OwnedProcesses.groupFor(executable) != originalGroup)

        onMain { FrpNetworkStore.selectInterface(missingName) }
        awaitWaiting()
        stopClient()
        onMain {
            FrpNetworkStore.selectInterface(wifi.interfaceName)
            FrpNetworkStore.refreshInterfaces()
        }
        assertStoppedStably()
        // 快速網路事件不得使最後一個停止指令失效。
        repeat(5) {
            FrpService.dispatch(context, FrpService.ACTION_START)
            onMain { FrpNetworkStore.selectInterface(missingName) }
            FrpService.dispatch(context, FrpService.ACTION_STOP)
            onMain { FrpNetworkStore.selectInterface(wifi.interfaceName) }
        }
        stopClient()
        assertStoppedStably()
    }

    @Test
    fun completedStopPreservesSubsequentNativeProbes() = runBlocking(Dispatchers.IO) {
        saveLocalConfig()
        repeat(4) {
            FrpService.dispatch(context, FrpService.ACTION_START)
            await("System-routed FRP client must start before Stop") {
                FrpStore.state.value.phase == FrpPhase.RUNNING && OwnedProcesses.isRunning(executable)
            }
            stopClient()
            assertEquals("Completed Stop must not kill a subsequent native version probe", 0, versionProbe(0L))
        }
    }

    @Test
    fun selectedWifiNativeClientPreservesLoopbackTcpAndUdpForwarding() {
        val wifi = availableWifi()
        assertEquals("Wi-Fi binding must succeed even when the optional fixture is absent", 0, versionProbe(wifi.handle))
        assumeTrue("Run scripts/frp-validation-server.py and reverse ports 17000/18080", probe("health"))
        val directory = File.createTempFile("frp-network-", "", context.cacheDir).apply {
            check(delete() && mkdir()) { "Unable to create FRP network fixture directory" }
        }
        val loopback = InetAddress.getByName("127.0.0.1")
        val tcp = ServerSocket(0, 0, loopback)
        val udp = DatagramSocket(0, loopback)
        val workers = Executors.newFixedThreadPool(2)
        var child: Process? = null
        try {
            workers.execute {
                runCatching {
                    while (!tcp.isClosed) {
                        tcp.accept().use { connection ->
                            connection.soTimeout = 5000
                            val payload = "xraydroid-frp-probe".toByteArray()
                            val received = ByteArray(payload.size)
                            java.io.DataInputStream(connection.getInputStream()).readFully(received)
                            if (received.contentEquals(payload)) connection.getOutputStream().write(received)
                        }
                    }
                }
            }
            workers.execute {
                runCatching {
                    while (!udp.isClosed) {
                        val packet = DatagramPacket(ByteArray(1024), 1024)
                        udp.receive(packet)
                        udp.send(packet)
                    }
                }
            }
            val config = File(directory, "frpc.toml").apply {
                writeText(
                    """
                    serverAddr = "127.0.0.1"
                    serverPort = 17000
                    loginFailExit = false
                    log.to = "/dev/null"
                    [[proxies]]
                    name = "android-tcp"
                    type = "tcp"
                    localIP = "127.0.0.1"
                    localPort = ${tcp.localPort}
                    remotePort = 16000
                    [[proxies]]
                    name = "android-udp"
                    type = "udp"
                    localIP = "127.0.0.1"
                    localPort = ${udp.localPort}
                    remotePort = 16001
                    """.trimIndent()
                )
            }
            val process = ProcessBuilder(executable, "-c", config.path).apply {
                directory(directory)
                environment()["XRAYDROID_FRP_NETWORK_HANDLE"] = wifi.handle.toString()
                redirectOutput(File("/dev/null"))
                redirectError(File("/dev/null"))
            }.start()
            child = process
            // 回送轉發只驗證選定網路下本機端點仍可用，不宣稱證明外部出站路由。
            await("Selected Wi-Fi client must forward TCP through the local fixture") {
                assertTrue("Selected-network native client must stay alive", process.isAlive)
                probe("tcp")
            }
            await("Selected Wi-Fi client must forward UDP through the local fixture") {
                assertTrue("Selected-network native client must stay alive", process.isAlive)
                probe("udp")
            }
        } finally {
            child?.let { terminate(it) }
            tcp.close()
            udp.close()
            workers.shutdownNow()
            assertTrue("Echo workers must stop", workers.awaitTermination(5, TimeUnit.SECONDS))
            directory.deleteRecursively()
        }
    }

    private suspend fun saveLocalConfig() {
        originalConfig = FrpStore.state.value.config
        val unusedPort = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        assertTrue(
            "Local credential-free configuration must validate and save",
            FrpStore.saveConfig(context, "serverAddr = '127.0.0.1'\nserverPort = $unusedPort\n")
        )
    }

    private fun availableWifi(): NetworkOption {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            FrpNetworkStore.state.value.options.firstOrNull { it.mode == OutboundNetworkMode.WIFI }?.let { return it }
            SystemClock.sleep(50)
        }
        // 有 Wi-Fi 卻已標示綁定失敗時必須失敗，不可誤判為環境缺少 Wi-Fi。
        assertFalse(
            "Visible Wi-Fi must not be silently skipped after binding failure",
            FrpNetworkStore.state.value.interfaces.any {
                it.mode == OutboundNetworkMode.WIFI && it.unavailableReason == R.string.network_reason_binding_failed
            }
        )
        assumeTrue("Available Wi-Fi is required on the development device", false)
        throw AssertionError("Wi-Fi assumption did not abort verification")
    }

    private fun versionProbe(handle: Long): Int {
        val child = ProcessBuilder(executable, "--version").apply {
            environment()["XRAYDROID_FRP_NETWORK_HANDLE"] = handle.toString()
            redirectOutput(File("/dev/null"))
            redirectError(File("/dev/null"))
        }.start()
        return try {
            assertTrue("Native version binding probe must finish", child.waitFor(5, TimeUnit.SECONDS))
            child.exitValue()
        } finally {
            terminate(child)
        }
    }

    private fun terminate(child: Process) {
        child.destroy()
        if (!child.waitFor(5, TimeUnit.SECONDS)) {
            child.destroyForcibly()
            assertTrue("Native child must terminate", child.waitFor(5, TimeUnit.SECONDS))
        }
    }

    private fun missingInterfaceName(): String = "xraydroid-frp-unavailable".also { name ->
        assertTrue(
            "Test interface must be absent",
            (NetworkStore.state.value.options + FrpNetworkStore.state.value.options).none {
                it.interfaceName == name
            }
        )
    }

    private fun restoreSelection(store: NetworkSelectionStore, state: NetworkState) {
        store.select(state.selectedMode)
        state.selectedInterfaceName?.let { store.selectInterface(it) }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun awaitRunning(interfaceName: String) {
        await("FRP must run on the selected interface") {
            FrpStore.state.value.phase == FrpPhase.RUNNING && OwnedProcesses.isRunning(executable)
        }
        assertTrue(
            "Applied FRP label must identify the selected interface",
            FrpStore.state.value.outboundNetworkLabel?.resolve(context)?.contains(interfaceName) == true
        )
    }

    private fun awaitWaiting() = await("Unavailable FRP network must stop the client and wait") {
        FrpStore.state.value.phase == FrpPhase.WAITING_FOR_NETWORK && !OwnedProcesses.isRunning(executable)
    }

    private fun stopClient() {
        // 已停止時不建立新的停止服務，避免與接下來的原生探針交錯。
        if (FrpStore.state.value.phase == FrpPhase.STOPPED && !OwnedProcesses.isRunning(executable)) return
        FrpService.dispatch(context, FrpService.ACTION_STOP)
        await("Stop must release the native FRP client") {
            FrpStore.state.value.phase == FrpPhase.STOPPED && !OwnedProcesses.isRunning(executable)
        }
        instrumentation.waitForIdleSync()
    }

    private fun assertStoppedStably() = assertStable("Stop must win over selection changes and delayed callbacks") {
        FrpStore.state.value.phase == FrpPhase.STOPPED && !OwnedProcesses.isRunning(executable)
    }

    private fun assertStable(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 2500
        do {
            assertTrue(message, condition())
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("$message; phase=${FrpStore.state.value.phase}")
    }

    private fun probe(path: String): Boolean = runCatching {
        val connection = URL("http://127.0.0.1:18080/$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 1000
            connection.readTimeout = 5000
            connection.responseCode == 200
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)
}
