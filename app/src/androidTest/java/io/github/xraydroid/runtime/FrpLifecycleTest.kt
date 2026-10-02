package io.github.xraydroid.runtime

import android.content.Intent
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xraydroid.MainActivity
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrpLifecycleTest {
    @Test
    fun externalTokenCommandsStopWithClientAndParentDeath() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".validation")) { "FRP verification requires an isolated validation application ID" }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        FrpStore.initialize(context)
        await("FRP settings must finish loading") { FrpStore.state.value.loaded }
        val original = FrpStore.state.value.config
        val originalUnsafe = FrpStore.state.value.allowUnsafeTokenCommand
        val config = """
            serverAddr = "127.0.0.1"
            serverPort = 17000
            auth.method = "token"
            auth.tokenSource.type = "exec"
            auth.tokenSource.exec.command = "/system/bin/sh"
            auth.tokenSource.exec.args = ["-c", "sleep 60; printf xraydroid-validation-placeholder"]
        """.trimIndent()
        val layout = FrpLayout(context)
        var launcher: java.lang.Process? = null
        var group: Int? = null
        try {
            FrpStore.setAllowUnsafeTokenCommand(context, false)
            assertFalse("External token commands must require opt-in", FrpStore.validateConfig(context, config))
            FrpStore.setAllowUnsafeTokenCommand(context, true)
            assertTrue("Opt-in must enable official token sources", FrpStore.saveConfig(context, config))
            FrpService.dispatch(context, FrpService.ACTION_START)
            await("Token command subprocess must actually start") {
                group = OwnedProcesses.groupFor(layout.executable.absolutePath)
                groupMembers(group) >= 2
            }
            FrpService.dispatch(context, FrpService.ACTION_STOP)
            await("Stop during startup must clean the whole token command session") {
                FrpStore.state.value.phase == FrpPhase.STOPPED && groupMembers(group) == 0
            }
            // 由短命父程序啟動相同核心，實際驗證父程序死亡保護，不終止測試 App。
            val shell = "\"${'$'}1\" -c \"${'$'}2\" --allow-unsafe=TokenSourceExec & wait"
            launcher = ProcessBuilder("/system/bin/sh", "-c", shell, "frp-parent-test", layout.executable.path, layout.config.path)
                .directory(layout.root)
                .redirectOutput(File("/dev/null"))
                .redirectError(File("/dev/null"))
                .start()
            await("Parent-death fixture must start a native client and command") {
                group = OwnedProcesses.groupFor(layout.executable.absolutePath)
                groupMembers(group) >= 2
            }
            launcher.destroyForcibly()
            assertTrue(launcher.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            await("Native parent-death protection must terminate all session members") { groupMembers(group) == 0 }
        } finally {
            launcher?.destroyForcibly()
            FrpService.dispatch(context, FrpService.ACTION_STOP)
            await("Final cleanup must stop the validation service") { FrpStore.state.value.phase == FrpPhase.STOPPED }
            OwnedProcesses.terminateGroup(group, OsConstants.SIGKILL)
            FrpStore.setAllowUnsafeTokenCommand(context, originalUnsafe)
            if (original.isNotBlank()) FrpStore.saveConfig(context, original)
        }
    }

    private fun groupMembers(group: Int?): Int {
        if (group == null) return 0
        return File("/proc").listFiles()?.count { directory ->
            directory.name.toIntOrNull() != null && runCatching {
                val fields = File(directory, "stat").readText().substringAfterLast(") ").split(' ')
                Os.stat(directory.path).st_uid == android.os.Process.myUid() && fields.getOrNull(0) != "Z" &&
                    fields.getOrNull(2)?.toIntOrNull() == group && fields.getOrNull(3)?.toIntOrNull() == group
            }.getOrDefault(false)
        } ?: 0
    }

    @Test
    fun fullClientForwardsTcpUdpAndRecoversIndependently() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".validation")) { "FRP verification requires an isolated validation application ID" }
        assumeTrue("Run scripts/frp-validation-server.py and reverse ports 17000/18080", probe("health"))
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("FRP settings must finish loading") { FrpStore.state.value.loaded }
        val originalConfig = FrpStore.state.value.config
        val layout = FrpLayout(context)
        val workers = Executors.newFixedThreadPool(2)
        val loopback = InetAddress.getByName("127.0.0.1")
        val tcp = ServerSocket(0, 0, loopback)
        val udp = DatagramSocket(0, loopback)
        workers.execute {
            runCatching {
                while (!tcp.isClosed) {
                    tcp.accept().use { connection ->
                        connection.soTimeout = 5000
                        val request = ByteArray(19)
                        var count = 0
                        while (count < request.size) {
                            val read = connection.getInputStream().read(request, count, request.size - count)
                            if (read < 0) break
                            count += read
                        }
                        connection.getOutputStream().write(request, 0, count)
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
        val config = """
            serverAddr = "127.0.0.1"
            serverPort = 17000
            transport.tls.enable = true
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
        try {
            assertTrue("Full configuration must validate and save", FrpStore.saveConfig(context, config))
            assertFalse("Invalid TOML must be rejected", FrpStore.saveConfig(context, "serverPort = \"invalid\""))
            assertEquals("Invalid save must preserve previous configuration", config, FrpStore.state.value.config)
            assertEquals("Invalid save must preserve disk configuration", config, layout.config.readText())
            // 訪客與外掛保留完整官方驗證，並非僅支援 TCP 範本。
            assertTrue(
                "STCP visitors and SOCKS5 plugins must validate",
                FrpStore.validateConfig(
                    context,
                    """
                        serverAddr = "127.0.0.1"
                        serverPort = 17000
                        [[proxies]]
                        name = "private-socks"
                        type = "stcp"
                        secretKey = "validation-placeholder"
                        plugin.type = "socks5"
                        [[visitors]]
                        name = "private-visitor"
                        type = "stcp"
                        serverName = "private-socks"
                        secretKey = "validation-placeholder"
                        bindAddr = "127.0.0.1"
                        bindPort = 16002
                    """.trimIndent()
                )
            )
            FrpService.dispatch(context, FrpService.ACTION_START)
            await("Both proxy registrations must run") { proxiesReady() }
            await("TCP must round trip through host frps and phone frpc") { probe("tcp") }
            await("UDP must round trip through host frps and phone frpc") { probe("udp") }
            assertFalse(
                "Starting frpc must not start 3x-ui",
                OwnedProcesses.isRunning("${context.applicationInfo.nativeLibraryDir}/libxui.so")
            )
            // 出站網路失效不得停止獨立的 frpc。
            instrumentation.runOnMainSync { NetworkStore.selectInterface("xraydroid-frp-missing") }
            assertTrue(probe("tcp"))
            instrumentation.runOnMainSync { NetworkStore.select(OutboundNetworkMode.SYSTEM) }

            FrpService.dispatch(context, FrpService.ACTION_START)
            SystemClock.sleep(2500)
            assertTrue("Repeated Start must retain status polling", proxiesReady())
            assertTrue(probe("stop"))
            await("Disconnected control must report reconnecting and clear stale proxies") {
                FrpStore.state.value.connection.phase == FrpConnectionPhase.RECONNECTING && FrpStore.state.value.proxies.isEmpty()
            }
            assertEquals("frpc must keep retrying after frps disconnects", FrpPhase.RUNNING, FrpStore.state.value.phase)
            assertTrue(probe("start"))
            await("frpc must reconnect without a manual restart") { proxiesReady() && probe("tcp") && probe("udp") }

            val previousGroup = OwnedProcesses.groupFor(layout.executable.absolutePath)
            FrpService.dispatch(context, FrpService.ACTION_RESTART)
            await("Restart must replace the native process and recover both mappings") {
                proxiesReady() &&
                    OwnedProcesses.groupFor(layout.executable.absolutePath)?.let { it != previousGroup } == true && probe("tcp")
            }
            OwnedProcesses.terminate(setOf(layout.executable.absolutePath), OsConstants.SIGKILL)
            await("Unexpected native exit must be visible and cleaned up") {
                FrpStore.state.value.phase == FrpPhase.ERROR && !OwnedProcesses.isRunning(layout.executable.absolutePath)
            }
            FrpService.dispatch(context, FrpService.ACTION_START)
            await("Manual start must recover after a crash") { proxiesReady() && probe("udp") }
            FrpService.dispatch(context, FrpService.ACTION_STOP)
            await("Stop must terminate the native client") {
                FrpStore.state.value.phase == FrpPhase.STOPPED && !OwnedProcesses.isRunning(layout.executable.absolutePath)
            }
            assertEquals("Stop must retain settings", config, layout.config.readText())
            repeat(3) {
                FrpService.dispatch(context, FrpService.ACTION_START)
                FrpService.dispatch(context, FrpService.ACTION_STOP)
            }
            await("The last Stop must win over rapid Start events") {
                FrpStore.state.value.phase == FrpPhase.STOPPED && !OwnedProcesses.isRunning(layout.executable.absolutePath)
            }
            SystemClock.sleep(2000)
            assertEquals(FrpPhase.STOPPED, FrpStore.state.value.phase)
        } finally {
            FrpService.dispatch(context, FrpService.ACTION_STOP)
            await("Final cleanup must terminate frpc") {
                FrpStore.state.value.phase == FrpPhase.STOPPED && !OwnedProcesses.isRunning(layout.executable.absolutePath)
            }
            if (originalConfig.isNotBlank()) FrpStore.saveConfig(context, originalConfig)
            tcp.close()
            udp.close()
            workers.shutdownNow()
            instrumentation.runOnMainSync { NetworkStore.select(OutboundNetworkMode.SYSTEM) }
            probe("start")
        }
    }

    @Test
    fun initialConnectionFailureIsVisibleWithoutAnyProxies() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".validation")) { "FRP verification requires an isolated validation application ID" }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        FrpStore.initialize(context)
        await("Settings must load") { FrpStore.state.value.loaded }
        val original = FrpStore.state.value.config
        val port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        try {
            assertTrue(FrpStore.saveConfig(context, "serverAddr = '127.0.0.1'\nserverPort = $port\n"))
            FrpService.dispatch(context, FrpService.ACTION_START)
            await("Failed initial login must report refused and keep retrying without proxies") {
                FrpStore.state.value.let {
                    it.phase == FrpPhase.RUNNING && it.connection.phase == FrpConnectionPhase.RETRYING &&
                        it.connection.error == "refused" && it.connection.attempts > 0 && it.proxies.isEmpty()
                }
            }
        } finally {
            FrpService.dispatch(context, FrpService.ACTION_STOP)
            await("Stop must clear connection state") {
                FrpStore.state.value.let { it.phase == FrpPhase.STOPPED && it.connection.phase == FrpConnectionPhase.UNKNOWN }
            }
            if (original.isNotBlank()) FrpStore.saveConfig(context, original)
        }
    }

    @Test
    fun loginWithNoProxiesDistinguishesSuccessAndServerRejection() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".validation")) { "FRP verification requires an isolated validation application ID" }
        assumeTrue("Run the local frps fixture", probe("health"))
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        FrpStore.initialize(context)
        await("Settings must load") { FrpStore.state.value.loaded }
        val original = FrpStore.state.value.config
        val config = "serverAddr = '127.0.0.1'\nserverPort = 17000\n"
        try {
            assertTrue(FrpStore.saveConfig(context, config))
            FrpService.dispatch(context, FrpService.ACTION_START)
            await("Successful login must be reported even with no proxies") {
                FrpStore.state.value.let { it.connection.phase == FrpConnectionPhase.CONNECTED && it.proxies.isEmpty() }
            }
            assertTrue(FrpStore.saveConfig(context, config + "auth.token = 'invalid-validation-placeholder'\n"))
            FrpService.dispatch(context, FrpService.ACTION_RESTART)
            await("Rejected login must report a safe reason without proxies") {
                FrpStore.state.value.let {
                    it.connection.phase == FrpConnectionPhase.RETRYING && it.connection.error == "login_rejected" && it.proxies.isEmpty()
                }
            }
        } finally {
            FrpService.dispatch(context, FrpService.ACTION_STOP)
            await("Final cleanup must stop frpc") { FrpStore.state.value.phase == FrpPhase.STOPPED }
            if (original.isNotBlank()) FrpStore.saveConfig(context, original)
        }
    }

    private fun proxiesReady(): Boolean = FrpStore.state.value.let { state ->
        state.phase == FrpPhase.RUNNING && state.connection.phase == FrpConnectionPhase.CONNECTED &&
            state.proxies.count { it.name in setOf("android-tcp", "android-udp") && it.status == "running" } == 2
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

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("$message; phase=${FrpStore.state.value.phase}")
    }
}
