package io.github.xraydroid.runtime

import java.io.File
import org.json.JSONObject

internal class TrafficReader(private val file: File, session: String, startedAt: Long) {
    private val freshness = TrafficFreshness(session, startedAt)

    @Synchronized
    fun read(now: Long): ProxyTraffic {
        val snapshot = runCatching {
            val bytes = file.inputStream().use { input ->
                val buffer = ByteArray(1025)
                var size = 0
                while (size < buffer.size) {
                    val count = input.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    size += count
                }
                check(size in 1..1024) { "Invalid traffic snapshot size" }
                buffer.copyOf(size)
            }
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val status = when (json.getString("state")) {
                "ready" -> TrafficStatus.READY
                "waiting" -> TrafficStatus.WAITING
                "unavailable" -> TrafficStatus.UNAVAILABLE
                else -> error("Invalid traffic snapshot state")
            }
            TrafficSnapshot(
                json.getString("session"),
                json.getLong("sequence"),
                ProxyTraffic(status, json.getDouble("upload"), json.getDouble("download"))
            )
        }.getOrNull()
        return freshness.accept(snapshot, now)
    }
}
