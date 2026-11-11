package io.github.xraydroid.runtime

enum class TrafficStatus { WAITING, READY, UNAVAILABLE }

data class ProxyTraffic(
    val status: TrafficStatus = TrafficStatus.WAITING,
    val uploadBytesPerSecond: Double = 0.0,
    val downloadBytesPerSecond: Double = 0.0
)

internal data class TrafficSnapshot(
    val session: String,
    val sequence: Long,
    val traffic: ProxyTraffic
)

internal class TrafficFreshness(private val session: String, private val startedAt: Long) {
    private var lastSequence = 0L
    private var updatedAt = startedAt

    fun accept(snapshot: TrafficSnapshot?, now: Long): ProxyTraffic {
        if (snapshot == null) {
            return ProxyTraffic(
                if (lastSequence == 0L && now - startedAt < STALE_AFTER_MS) TrafficStatus.WAITING else TrafficStatus.UNAVAILABLE
            )
        }
        val traffic = snapshot.traffic
        if (snapshot.session != session || snapshot.sequence <= 0 || snapshot.sequence < lastSequence ||
            !traffic.uploadBytesPerSecond.isFinite() || !traffic.downloadBytesPerSecond.isFinite() ||
            traffic.uploadBytesPerSecond < 0 || traffic.downloadBytesPerSecond < 0
        ) {
            return ProxyTraffic(TrafficStatus.UNAVAILABLE)
        }
        if (snapshot.sequence > lastSequence) {
            lastSequence = snapshot.sequence
            updatedAt = now
        }
        if (now - updatedAt >= STALE_AFTER_MS) return ProxyTraffic(TrafficStatus.UNAVAILABLE)
        return if (traffic.status == TrafficStatus.READY) traffic else ProxyTraffic(traffic.status)
    }

    private companion object {
        const val STALE_AFTER_MS = 3000L
    }
}
