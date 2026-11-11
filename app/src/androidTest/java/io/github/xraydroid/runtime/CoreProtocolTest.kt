package io.github.xraydroid.runtime

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreProtocolTest {
    @Test
    fun shadowsocksAeadTransfersTcpAndUdp() = verifyProtocol("shadowsocks", "aes-128-gcm")

    @Test
    fun shadowsocks2022TransfersTcpAndUdp() = verifyProtocol("shadowsocks", "2022-blake3-aes-128-gcm")

    @Test
    fun vlessRealityVisionTransfersTcpAndUdp() = verifyProtocol("vless")

    @Test
    fun hysteria2TransfersTcpAndUdp() = verifyProtocol("hysteria")

    private fun verifyProtocol(protocol: String, method: String = "") {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "io.github.xraydroid.validation") {
            "Protocol verification requires the isolated validation application ID"
        }
        val directory = File(context.cacheDir, "core-protocol-${UUID.randomUUID()}").apply { mkdirs() }
        val processes = mutableListOf<Process>()
        val loopback = InetAddress.getByName("127.0.0.1")
        try {
            val serverPort = if (protocol == "hysteria") udpPort() else tcpPort()
            val socksPort = tcpPort()
            val id = UUID.randomUUID().toString()
            val fixturePassword = if (method.startsWith("2022")) {
                android.util.Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, android.util.Base64.NO_WRAP)
            } else {
                "disposable-protocol-fixture"
            }
            val serverSettings: JSONObject
            val clientSettings: JSONObject
            val serverStream = JSONObject().put("network", "raw")
            val clientStream = JSONObject().put("network", "raw")
            when (protocol) {
                "shadowsocks" -> {
                    serverSettings = JSONObject().put("method", method).put("password", fixturePassword).put("network", "tcp,udp")
                    clientSettings = JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject().put("address", "127.0.0.1").put("port", serverPort)
                                .put("method", method).put("password", fixturePassword)
                        )
                    )
                }
                "vless" -> {
                    val certificate = JSONObject(command(context, directory, "tls", "cert", "-domain=localhost", "-json"))
                    val targetPort = tcpPort()
                    val target = JSONObject().put("listen", "127.0.0.1").put("port", targetPort)
                        .put("protocol", "trojan")
                        .put("settings", JSONObject().put("clients", JSONArray().put(JSONObject().put("password", fixturePassword))))
                        .put(
                            "streamSettings",
                            JSONObject().put("network", "raw").put("security", "tls")
                                .put(
                                    "tlsSettings",
                                    JSONObject().put("alpn", JSONArray().put("h2")).put("certificates", JSONArray().put(certificate))
                                )
                        )
                    processes += startCore(context, directory, "target", config(JSONArray().put(target)))
                    awaitListening(targetPort, processes.last())
                    val keyOutput = command(context, directory, "x25519")
                    fun key(label: String): String = keyOutput.lineSequence().first { it.startsWith("$label:") }.substringAfter(":").trim()
                    serverSettings = JSONObject().put("decryption", "none").put(
                        "clients", JSONArray().put(JSONObject().put("id", id).put("flow", "xtls-rprx-vision"))
                    )
                    clientSettings = JSONObject().put(
                        "vnext",
                        JSONArray().put(
                            JSONObject().put("address", "127.0.0.1").put("port", serverPort)
                                .put(
                                    "users",
                                    JSONArray().put(JSONObject().put("id", id).put("encryption", "none").put("flow", "xtls-rprx-vision"))
                                )
                        )
                    )
                    serverStream.put("security", "reality").put(
                        "realitySettings",
                        JSONObject().put("target", "127.0.0.1:$targetPort").put("serverNames", JSONArray().put("localhost"))
                            .put("privateKey", key("PrivateKey")).put("shortIds", JSONArray().put("0123456789abcdef"))
                    )
                    clientStream.put("security", "reality").put(
                        "realitySettings",
                        JSONObject().put("serverName", "localhost").put("fingerprint", "chrome")
                            .put("password", key("Password (PublicKey)")).put("shortId", "0123456789abcdef")
                    )
                }
                else -> {
                    val certificate = JSONObject(command(context, directory, "tls", "cert", "-domain=localhost", "-json"))
                    serverSettings = JSONObject().put("version", 2).put(
                        "clients", JSONArray().put(JSONObject().put("auth", fixturePassword))
                    )
                    clientSettings = JSONObject().put("version", 2).put("address", "127.0.0.1").put("port", serverPort)
                    serverStream.put("network", "hysteria").put("security", "tls")
                        .put("hysteriaSettings", JSONObject().put("version", 2))
                        .put(
                            "tlsSettings",
                            JSONObject().put("alpn", JSONArray().put("h3")).put("certificates", JSONArray().put(certificate))
                        )
                    clientStream.put("network", "hysteria").put("security", "tls")
                        .put("hysteriaSettings", JSONObject().put("version", 2).put("auth", fixturePassword))
                        .put(
                            "tlsSettings",
                            JSONObject().put("serverName", "localhost").put("alpn", JSONArray().put("h3"))
                                .put(
                                    "certificates",
                                    JSONArray().put(
                                        JSONObject().put("certificate", certificate.getJSONArray("certificate")).put("usage", "verify")
                                    )
                                )
                        )
                }
            }
            val inbound = JSONObject().put("listen", "127.0.0.1").put("port", serverPort)
                .put("protocol", protocol).put("settings", serverSettings).put("streamSettings", serverStream)
            processes += startCore(context, directory, "server", config(JSONArray().put(inbound)))
            if (protocol != "hysteria") awaitListening(serverPort, processes.last())
            val outbound = JSONObject().put("protocol", protocol).put("settings", clientSettings).put("streamSettings", clientStream)
            val socks = JSONObject().put("listen", "127.0.0.1").put("port", socksPort).put("protocol", "socks")
                .put("settings", JSONObject().put("udp", true))
            processes += startCore(context, directory, "client", config(JSONArray().put(socks), JSONArray().put(outbound)))
            awaitListening(socksPort, processes.last())
            val payload = ByteArray(32768).also { SecureRandom().nextBytes(it) }
            ServerSocket(0, 1, loopback).use { echo ->
                echo.soTimeout = 15000
                val worker = thread {
                    runCatching {
                        echo.accept().use { socket ->
                            socket.soTimeout = 15000
                            val received = ByteArray(payload.size)
                            DataInputStream(socket.inputStream).readFully(received)
                            socket.outputStream.write(received)
                        }
                    }
                }
                try {
                    socksConnect(socksPort, echo.localPort).use { socket ->
                        socket.outputStream.write(payload)
                        val received = ByteArray(payload.size)
                        DataInputStream(socket.inputStream).readFully(received)
                        assertArrayEquals("$protocol must transfer the complete TCP payload", payload, received)
                    }
                } finally {
                    echo.close()
                    worker.join(16000)
                }
            }
            DatagramSocket(0, loopback).use { echo ->
                echo.soTimeout = 15000
                val worker = thread {
                    runCatching {
                        val packet = DatagramPacket(ByteArray(4096), 4096)
                        echo.receive(packet)
                        echo.send(packet)
                    }
                }
                try {
                    handshake(socksPort).use { control ->
                        control.outputStream.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
                        val relay = reply(control)
                        DatagramSocket().use { udp ->
                            udp.soTimeout = 15000
                            val expected = payload.copyOf(1024)
                            val header = byteArrayOf(0, 0, 0, 1, 127, 0, 0, 1, (echo.localPort shr 8).toByte(), echo.localPort.toByte())
                            val request = header + expected
                            udp.send(DatagramPacket(request, request.size, loopback, relay))
                            val response = DatagramPacket(ByteArray(4096), 4096)
                            udp.receive(response)
                            assertEquals(1, response.data[3].toInt())
                            assertArrayEquals(
                                "$protocol must transfer the complete UDP payload",
                                expected,
                                response.data.copyOfRange(10, response.length)
                            )
                        }
                    }
                } finally {
                    echo.close()
                    worker.join(16000)
                }
            }
            assertTrue("All packaged core processes must survive protocol traffic", processes.all { it.isAlive })
        } finally {
            processes.asReversed().forEach { process ->
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor()
            }
            directory.deleteRecursively()
        }
    }

    private fun config(inbounds: JSONArray, outbounds: JSONArray? = null): JSONObject = JSONObject()
        .put("log", JSONObject().put("loglevel", "none"))
        .put("inbounds", inbounds)
        .put(
            "outbounds",
            outbounds ?: JSONArray().put(
                JSONObject().put("protocol", "freedom").put(
                    "settings", JSONObject().put("finalRules", JSONArray().put(JSONObject().put("action", "allow")))
                )
            )
        )

    private fun startCore(context: Context, directory: File, name: String, config: JSONObject): Process {
        val file = File(directory, "$name.json").apply { writeText(config.toString()) }
        return ProcessBuilder("${context.applicationInfo.nativeLibraryDir}/libxray.so", "run", "-c", file.path)
            .directory(directory).redirectOutput(File("/dev/null")).redirectError(File("/dev/null")).start()
    }

    private fun command(context: Context, directory: File, vararg arguments: String): String {
        val output = File(directory, "generated.json")
        val process = ProcessBuilder(listOf("${context.applicationInfo.nativeLibraryDir}/libxray.so") + arguments)
            .directory(directory).redirectOutput(output).redirectError(File("/dev/null")).start()
        try {
            assertTrue("Core fixture generation must finish", process.waitFor(10, TimeUnit.SECONDS))
            assertEquals("Core fixture generation must succeed", 0, process.exitValue())
            return output.readText()
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
            output.delete()
        }
    }

    private fun awaitListening(port: Int, process: Process) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline && process.isAlive) {
            if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Packaged core did not open the protocol listener")
    }

    private fun tcpPort(): Int = ServerSocket(0).use { it.localPort }

    private fun udpPort(): Int = DatagramSocket(0).use { it.localPort }

    private fun handshake(port: Int): Socket = Socket("127.0.0.1", port).apply {
        soTimeout = 15000
        outputStream.write(byteArrayOf(5, 1, 0))
        val input = DataInputStream(inputStream)
        assertEquals(5, input.readUnsignedByte())
        assertEquals(0, input.readUnsignedByte())
    }

    private fun socksConnect(port: Int, destination: Int): Socket = handshake(port).apply {
        outputStream.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, (destination shr 8).toByte(), destination.toByte()))
        reply(this)
    }

    private fun reply(socket: Socket): Int {
        val input = DataInputStream(socket.inputStream)
        assertEquals(5, input.readUnsignedByte())
        assertEquals("SOCKS request must succeed", 0, input.readUnsignedByte())
        assertEquals(0, input.readUnsignedByte())
        val size = when (input.readUnsignedByte()) {
            1 -> 4
            4 -> 16
            else -> throw AssertionError("Unexpected SOCKS address type")
        }
        input.readFully(ByteArray(size))
        return input.readUnsignedShort()
    }
}
