package io.github.xraydroid.runtime

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xraydroid.MainActivity
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProxyTrafficDeviceTest {
    @Test
    fun liveOutboundRatesFollowProxyTransfersAndClearAfterStop() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(
            context.packageName == "io.github.xraydroid.validation"
        ) { "Traffic verification requires the isolated validation application ID" }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            XuiService.dispatch(context, XuiService.ACTION_START)
            await("Service must start") { ServerStore.state.value.phase == ServerPhase.RUNNING }
            await("Idle sampling must become ready") { ServerStore.state.value.traffic.status == TrafficStatus.READY }
            val upload = ByteArray(16 * 1024) { (it % 127).toByte() }
            val download = ByteArray(64 * 1024) { (it % 113).toByte() }
            val fixtureFailure = AtomicReference<Throwable?>()
            ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 20000
                PanelManagementProbe().withTcpForward(server.localPort) { proxyPort ->
                    val fixture = thread(name = "traffic-fixture") {
                        runCatching {
                            // 第一個連線是面板測試用的監聽探測。
                            while (true) {
                                val complete = server.accept().use { socket ->
                                    socket.soTimeout = 5000
                                    val input = DataInputStream(socket.inputStream)
                                    val first = input.read()
                                    if (first < 0) return@use false
                                    val received = ByteArray(upload.size)
                                    received[0] = first.toByte()
                                    input.readFully(received, 1, received.size - 1)
                                    assertArrayEquals(upload, received)
                                    socket.outputStream.write(download)
                                    repeat(99) {
                                        input.readFully(received)
                                        assertArrayEquals(upload, received)
                                        socket.outputStream.write(download)
                                    }
                                    true
                                }
                                if (complete) break
                            }
                        }.onFailure { fixtureFailure.set(it) }
                    }
                    val client = thread(name = "traffic-client") {
                        runCatching {
                            Socket("127.0.0.1", proxyPort).use { socket ->
                                socket.soTimeout = 5000
                                val input = DataInputStream(socket.inputStream)
                                val received = ByteArray(download.size)
                                repeat(100) {
                                    socket.outputStream.write(upload)
                                    input.readFully(received)
                                    assertArrayEquals(download, received)
                                    SystemClock.sleep(100)
                                }
                            }
                        }.onFailure { fixtureFailure.set(it) }
                    }
                    try {
                        await("Proxy transfer must publish distinct upload and download rates", 15000) {
                            fixtureFailure.get()?.let { throw AssertionError("Traffic fixture failed", it) }
                            val traffic = ServerStore.state.value.traffic
                            traffic.status == TrafficStatus.READY && traffic.uploadBytesPerSecond > 50000 &&
                                traffic.downloadBytesPerSecond > traffic.uploadBytesPerSecond * 2
                        }
                    } finally {
                        client.join(15000)
                        fixture.join(7000)
                    }
                    fixtureFailure.get()?.let { throw AssertionError("Traffic fixture failed", it) }
                    assertTrue("Traffic threads must finish", !client.isAlive && !fixture.isAlive)
                }
            }
            await("Completed transfers must return to zero speed", 10000) {
                ServerStore.state.value.traffic == ProxyTraffic(TrafficStatus.READY)
            }
            XuiService.dispatch(context, XuiService.ACTION_START)
            await("Duplicate start must keep traffic sampling active") { ServerStore.state.value.traffic.status == TrafficStatus.READY }
        } finally {
            XuiService.dispatch(context, XuiService.ACTION_STOP)
            await("Service must stop") { ServerStore.state.value.phase == ServerPhase.STOPPED }
        }
        assertEquals("Stopped service must clear transfer rates", ProxyTraffic(), ServerStore.state.value.traffic)
    }

    private fun await(message: String, timeout: Long = 60000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError(message)
    }
}
