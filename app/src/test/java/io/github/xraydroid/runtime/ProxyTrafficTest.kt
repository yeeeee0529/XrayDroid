package io.github.xraydroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyTrafficTest {
    @Test
    fun lifecycleTransitionsClearRatesAndRejectLateUpdates() {
        ServerStore.transition(ServerPhase.RUNNING, null)
        ServerStore.updateTraffic(ProxyTraffic(TrafficStatus.READY, 1000.0, 3000.0))
        assertEquals(TrafficStatus.READY, ServerStore.state.value.traffic.status)
        for (phase in listOf(
            ServerPhase.STOPPING,
            ServerPhase.STOPPED,
            ServerPhase.ERROR,
            ServerPhase.WAITING_FOR_NETWORK,
            ServerPhase.STARTING
        )) {
            ServerStore.transition(phase, null)
            ServerStore.updateTraffic(ProxyTraffic(TrafficStatus.READY, 999.0, 999.0))
            assertEquals(ProxyTraffic(), ServerStore.state.value.traffic)
        }
        ServerStore.transition(ServerPhase.STOPPED, null)
    }

    @Test
    fun staleSnapshotsDisappearAndNewSamplesRecover() {
        val freshness = TrafficFreshness("current", 100)
        val snapshot = snapshot(sequence = 1)
        assertEquals(snapshot.traffic, freshness.accept(snapshot, 100))
        assertEquals(snapshot.traffic, freshness.accept(snapshot, 3099))
        assertEquals(ProxyTraffic(TrafficStatus.UNAVAILABLE), freshness.accept(snapshot, 3100))
        assertEquals(snapshot.traffic, freshness.accept(snapshot.copy(sequence = 2), 4100))
    }

    @Test
    fun oldProcessesAndInvalidRatesCannotPublishSpeed() {
        val freshness = TrafficFreshness("current", 0)
        val invalid = listOf(
            snapshot().copy(session = "old"),
            snapshot(sequence = 0),
            snapshot(upload = -1.0),
            snapshot(upload = Double.NaN),
            snapshot(download = Double.POSITIVE_INFINITY)
        )
        invalid.forEach {
            assertEquals(ProxyTraffic(TrafficStatus.UNAVAILABLE), freshness.accept(it, 0))
        }
        assertEquals(snapshot().traffic, freshness.accept(snapshot(), 100))
    }

    @Test
    fun missingOrFailedSamplesClearPreviousSpeed() {
        val freshness = TrafficFreshness("current", 0)
        assertEquals(ProxyTraffic(), freshness.accept(null, 0))
        assertEquals(ProxyTraffic(TrafficStatus.UNAVAILABLE), freshness.accept(null, 3000))
        freshness.accept(snapshot(), 4000)
        assertEquals(ProxyTraffic(TrafficStatus.UNAVAILABLE), freshness.accept(null, 4001))
        assertEquals(
            ProxyTraffic(TrafficStatus.UNAVAILABLE),
            freshness.accept(snapshot(sequence = 2).copy(traffic = ProxyTraffic(TrafficStatus.UNAVAILABLE, 999.0, 999.0)), 5000)
        )
    }

    @Test
    fun idleIsAValidZeroSpeedAndSequenceCannotGoBackwards() {
        val freshness = TrafficFreshness("current", 0)
        val idle = snapshot(sequence = 2, upload = 0.0, download = 0.0)
        assertEquals(ProxyTraffic(TrafficStatus.READY), freshness.accept(idle, 100))
        assertEquals(ProxyTraffic(TrafficStatus.UNAVAILABLE), freshness.accept(snapshot(sequence = 1), 200))
    }

    private fun snapshot(sequence: Long = 1, upload: Double = 1000.0, download: Double = 3000.0) = TrafficSnapshot(
        "current",
        sequence,
        ProxyTraffic(TrafficStatus.READY, upload, download)
    )
}
