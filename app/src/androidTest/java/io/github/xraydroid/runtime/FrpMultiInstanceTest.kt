package io.github.xraydroid.runtime

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xraydroid.MainActivity
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrpMultiInstanceTest {
    @Test
    fun clientsRestartStopAndWaitForNetworkIndependently() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".validation")) { "FRP verification requires an isolated validation application ID" }
        assumeTrue(
            "Reverse host frps fixture ports 17000 and 17001",
            (17000..17001).all { port ->
                runCatching {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1000) }
                    true
                }.getOrDefault(false)
            }
        )
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        FrpStore.initialize(context)
        await("Instance catalog must load") { FrpStore.instancesLoaded.value }
        val ids = (1..2).map { number ->
            val name = "multi-instance-validation-$number"
            FrpStore.instances.value.firstOrNull { it.name == name }?.id ?: checkNotNull(FrpStore.createInstance(context, name))
        }
        val stores = ids.map { FrpStore.forInstance(it) }
        stores.forEach { store -> await("Instance settings must load") { store.state.value.loaded } }
        val originals = stores.map { it.state.value.config }
        val layouts = ids.map { FrpLayout(context, it) }
        fun group(index: Int) = OwnedProcesses.groupFor(layouts[index].executable.absolutePath, layouts[index].config.absolutePath)
        try {
            stores.forEachIndexed { index, store ->
                val port = 17000 + index
                assertTrue(store.saveConfig(context, "serverAddr = '127.0.0.1'\nserverPort = $port\n"))
            }
            ids.forEach { FrpService.dispatch(context, FrpService.ACTION_START, it) }
            await("Both clients must run simultaneously with distinct sessions") {
                stores.all {
                    it.state.value.phase == FrpPhase.RUNNING && it.state.value.connection.phase == FrpConnectionPhase.CONNECTED
                } && group(0) != null && group(1) != null && group(0) != group(1)
            }
            val firstGroup = group(0)
            val secondGroup = group(1)
            FrpService.dispatch(context, FrpService.ACTION_RESTART, ids[0])
            await("Restart must replace only the selected client") {
                stores[0].state.value.phase == FrpPhase.RUNNING &&
                    stores[0].state.value.connection.phase == FrpConnectionPhase.CONNECTED && group(0) != firstGroup
            }
            assertEquals("Other client must retain its session", secondGroup, group(1))
            assertEquals(FrpPhase.RUNNING, stores[1].state.value.phase)
            instrumentation.runOnMainSync { FrpNetworkStore.forInstance(ids[0]).selectInterface("xraydroid-frp-missing") }
            await("Missing network must pause only the selected instance") {
                stores[0].state.value.phase == FrpPhase.WAITING_FOR_NETWORK && group(0) == null
            }
            assertEquals(secondGroup, group(1))
            assertEquals(FrpPhase.RUNNING, stores[1].state.value.phase)
            FrpService.dispatch(context, FrpService.ACTION_STOP, ids[0])
            await("Stop must finish the waiting instance") { stores[0].state.value.phase == FrpPhase.STOPPED }
            assertEquals(secondGroup, group(1))
            assertEquals(FrpPhase.RUNNING, stores[1].state.value.phase)
            repeat(3) {
                FrpService.dispatch(context, FrpService.ACTION_START, ids[0])
                FrpService.dispatch(context, FrpService.ACTION_STOP, ids[0])
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(1500)
            await("Last stop must win without stopping the other client") { stores[0].state.value.phase == FrpPhase.STOPPED }
            assertEquals(secondGroup, group(1))
        } finally {
            ids.forEach { FrpService.dispatch(context, FrpService.ACTION_STOP, it) }
            await("Final cleanup must release both sessions") {
                stores.all { it.state.value.phase == FrpPhase.STOPPED } && ids.indices.all { group(it) == null }
            }
            instrumentation.runOnMainSync {
                ids.forEach { id ->
                    FrpNetworkStore.acquire(context, id).select(OutboundNetworkMode.SYSTEM)
                    FrpNetworkStore.release(id)
                }
            }
            stores.forEachIndexed { index, store ->
                if (originals[index].isNotBlank()) {
                    assertTrue(
                        "Original configuration must restore",
                        store.saveConfig(context, originals[index])
                    )
                }
            }
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        throw AssertionError(message)
    }
}
