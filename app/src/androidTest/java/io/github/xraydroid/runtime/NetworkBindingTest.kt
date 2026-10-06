package io.github.xraydroid.runtime

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkBindingTest {
    @Test
    fun wifiCoreUsesSelectedNetworkForTcpAndUdp() = verifyTransport(NetworkCapabilities.TRANSPORT_WIFI)

    @Test
    fun cellularCoreUsesSelectedNetworkForTcpAndUdp() = verifyTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

    @Test
    fun invalidNetworkHandleCannotFallBackToDefaultNetwork() {
        withCore(Long.MAX_VALUE, allowStartupRejection = true) { port ->
            val connected = runCatching {
                socksConnect(port, "1.1.1.1", 443).use { }
            }.isSuccess
            assertTrue("Unavailable network handle must reject outbound connections", !connected)
        }
    }

    private fun verifyTransport(transport: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val available = CountDownLatch(1)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                available.countDown()
            }
        }
        connectivity.requestNetwork(
            NetworkRequest.Builder()
                .addTransportType(transport)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            callback
        )
        try {
            available.await(15, TimeUnit.SECONDS)
            verifyAvailableTransport(connectivity, transport)
        } finally {
            connectivity.unregisterNetworkCallback(callback)
        }
    }

    private fun verifyAvailableTransport(connectivity: ConnectivityManager, transport: Int) {
        val network = connectivity.allNetworks.firstOrNull { candidate ->
            connectivity.getNetworkCapabilities(candidate)?.let {
                it.hasTransport(transport) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            } == true
        }
        assumeTrue("Requested transport is unavailable on this development device", network != null)
        val selected = requireNotNull(network)
        val dnsServer = connectivity.getLinkProperties(selected)?.dnsServers?.filterIsInstance<Inet4Address>()?.firstOrNull()
        assumeTrue("The selected network must expose an IPv4 DNS server for the UDP probe", dnsServer != null)
        val expected = observedAddress(selected)
        withCore(selected.networkHandle) { port ->
            val actual = observedAddressThroughCore(port)
            // 不將裝置對外 IP 寫進測試輸出。
            assertTrue("Core TCP egress must match the explicitly selected Android network", expected == actual)
            verifyUdpDns(port, requireNotNull(dnsServer))
        }
    }

    private fun observedAddress(network: Network): String {
        val connection = network.openConnection(URL("https://api.ipify.org")) as HttpURLConnection
        return try {
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            assertEquals(200, connection.responseCode)
            extractAddress(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun observedAddressThroughCore(port: Int): String = socksConnect(port, "api.ipify.org", 443).use { socket ->
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        (factory.createSocket(socket, "api.ipify.org", 443, true) as SSLSocket).use { tls ->
            tls.soTimeout = 15000
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.startHandshake()
            tls.outputStream.write(
                "GET / HTTP/1.0\r\nHost: api.ipify.org\r\nConnection: close\r\n\r\n".toByteArray()
            )
            tls.outputStream.flush()
            val response = tls.inputStream.bufferedReader().use { it.readText() }
            assertTrue("Core HTTPS request must succeed", response.startsWith("HTTP/1.1 200") || response.startsWith("HTTP/1.0 200"))
            extractAddress(response.substringAfter("\r\n\r\n"))
        }
    }

    private fun extractAddress(body: String): String = requireNotNull(Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b").find(body)?.value) {
        "IP verification endpoint returned an unexpected response"
    }

    private fun verifyUdpDns(port: Int, dnsServer: Inet4Address) {
        socksHandshake(port).use { control ->
            control.outputStream.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
            val relay = readSocksReply(control)
            DatagramSocket().use { udp ->
                udp.soTimeout = 15000
                val dns = byteArrayOf(
                    0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
                    7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1
                )
                val request = byteArrayOf(0, 0, 0, 1) + dnsServer.address + byteArrayOf(0, 53) + dns
                udp.send(DatagramPacket(request, request.size, InetAddress.getByName("127.0.0.1"), relay.port))
                val response = DatagramPacket(ByteArray(4096), 4096)
                udp.receive(response)
                assertTrue("SOCKS UDP DNS response must contain a complete IPv4 header", response.length >= 22)
                assertEquals(1, response.data[3].toInt())
                assertEquals(0x12, response.data[10].toInt() and 255)
                assertEquals(0x34, response.data[11].toInt() and 255)
                assertTrue("DNS packet must be a successful response", response.data[12].toInt() and 128 != 0)
                assertEquals(0, response.data[13].toInt() and 15)
                val answers = ((response.data[16].toInt() and 255) shl 8) or
                    (response.data[17].toInt() and 255)
                assertTrue("DNS response must contain an answer", answers > 0)
            }
        }
    }

    private fun socksHandshake(port: Int): Socket {
        val socket = Socket()
        try {
            socket.soTimeout = 15000
            socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
            socket.outputStream.write(byteArrayOf(5, 1, 0))
            val input = DataInputStream(socket.inputStream)
            assertEquals(5, input.readUnsignedByte())
            assertEquals(0, input.readUnsignedByte())
            return socket
        } catch (error: Throwable) {
            socket.close()
            throw error
        }
    }

    private fun socksConnect(port: Int, host: String, destinationPort: Int): Socket {
        val socket = socksHandshake(port)
        try {
            val encoded = host.toByteArray(Charsets.US_ASCII)
            socket.outputStream.write(
                byteArrayOf(5, 1, 0, 3, encoded.size.toByte()) + encoded +
                    byteArrayOf((destinationPort shr 8).toByte(), destinationPort.toByte())
            )
            readSocksReply(socket)
            return socket
        } catch (error: Throwable) {
            socket.close()
            throw error
        }
    }

    private fun readSocksReply(socket: Socket): InetSocketAddress {
        val input = DataInputStream(socket.inputStream)
        assertEquals(5, input.readUnsignedByte())
        assertEquals("SOCKS request must succeed", 0, input.readUnsignedByte())
        assertEquals(0, input.readUnsignedByte())
        val size = when (input.readUnsignedByte()) {
            1 -> 4
            4 -> 16
            else -> throw AssertionError("Unexpected SOCKS reply address type")
        }
        val address = ByteArray(size).also { input.readFully(it) }
        return InetSocketAddress(InetAddress.getByAddress(address), input.readUnsignedShort())
    }

    private fun withCore(handle: Long, allowStartupRejection: Boolean = false, verify: (Int) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".validation")) {
            "Network verification requires an isolated validation application ID"
        }
        val directory = File.createTempFile("network-probe-", "", context.cacheDir).apply {
            delete()
            mkdir()
        }
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val config = File(directory, "config.json").apply {
            writeText(
                """
                {"log":{"loglevel":"none"},
                 "inbounds":[{"listen":"127.0.0.1","port":$port,"protocol":"socks","settings":{"auth":"noauth","udp":true}}],
                 "outbounds":[{"protocol":"freedom","settings":{"domainStrategy":"UseIPv4"}}]}
                """.trimIndent()
            )
        }
        val process = ProcessBuilder(
            "${context.applicationInfo.nativeLibraryDir}/libxray.so",
            "run",
            "-c",
            config.path
        ).directory(directory).apply {
            environment()["XRAY_ANDROID_NETWORK_HANDLE"] = handle.toString()
            redirectOutput(File("/dev/null"))
            redirectError(File("/dev/null"))
        }.start()
        try {
            val deadline = SystemClock.elapsedRealtime() + 10000
            while (SystemClock.elapsedRealtime() < deadline && process.isAlive) {
                if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) break
                SystemClock.sleep(50)
            }
            if (allowStartupRejection && !process.isAlive) return
            assertTrue("Standalone core must start without binding loopback listeners", process.isAlive)
            verify(port)
        } finally {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
            directory.deleteRecursively()
        }
    }
}
