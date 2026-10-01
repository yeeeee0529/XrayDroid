package io.github.xraydroid.runtime

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal class PanelManagementProbe {
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private var csrf = ""

    fun verifyLoginAndVlessInbound(context: Context) {
        csrf = request("/csrf-token").getString("obj")
        request("/login", JSONObject().put("username", "admin").put("password", "admin"))
        csrf = request("/csrf-token").getString("obj")
        val disk = awaitDiskStatus()
        val filesystem = StatFs(context.filesDir.path)
        assertEquals("Panel disk capacity must match app data filesystem", filesystem.totalBytes, disk.getLong("total"))
        assertTrue("Panel disk usage must be within capacity", disk.getLong("current") in 0..filesystem.totalBytes)
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val inbound = JSONObject()
            .put("remark", "android-e2e")
            .put("enable", true)
            .put("listen", "127.0.0.1")
            .put("port", port)
            .put("protocol", "vless")
            .put("settings", """{"clients":[],"decryption":"none"}""")
            .put("streamSettings", """{"network":"tcp","security":"none","tcpSettings":{"header":{"type":"none"}}}""")
            .put("sniffing", """{"enabled":false}""")
            .put("trafficReset", "never")
        val id = request("/panel/api/inbounds/add", inbound).getJSONObject("obj").getInt("id")
        try {
            awaitListening(port, true)
            assertTrue("New VLESS inbound must be served by the packaged Xray core", listening(port))
        } finally {
            request("/panel/api/inbounds/del/$id", JSONObject())
        }
        awaitListening(port, false)
    }

    private fun awaitDiskStatus(): JSONObject {
        // 面板 HTTP 就緒時，第一筆背景統計可能尚未完成。
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            val status = request("/panel/api/server/status").optJSONObject("obj")
            if (status != null) return status.getJSONObject("disk")
            SystemClock.sleep(100)
        }
        throw AssertionError("Panel did not publish storage statistics")
    }

    private fun request(path: String, body: JSONObject? = null): JSONObject {
        val url = URL("http://127.0.0.1:2053$path")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 3000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            cookies.get(url.toURI(), emptyMap()).forEach { (key, values) ->
                connection.setRequestProperty(key, values.joinToString("; "))
            }
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-CSRF-Token", csrf)
                connection.setRequestProperty("Origin", "http://127.0.0.1:2053")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            check(connection.responseCode == 200) { "Panel HTTP request failed at $path" }
            cookies.put(url.toURI(), connection.headerFields)
            val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            check(response.getBoolean("success")) { "Panel operation failed at $path" }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun awaitListening(port: Int, expected: Boolean) {
        // 上游 AddInbound 的 RPC 未就緒時仍回報儲存成功，改由待重啟工作套用設定。
        // internal/web/web.go 的 cadenceXrayRestart 為 30 秒，再保留 10 秒供核心重啟。
        val deadline = SystemClock.elapsedRealtime() + 40000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (listening(port) == expected) return
            SystemClock.sleep(100)
        }
        throw AssertionError("VLESS inbound listening state did not become $expected")
    }

    private fun listening(port: Int): Boolean = runCatching {
        Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 500) }
        true
    }.getOrDefault(false)
}
