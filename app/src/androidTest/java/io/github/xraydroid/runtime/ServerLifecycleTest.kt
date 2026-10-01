package io.github.xraydroid.runtime

import android.content.Intent
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xraydroid.MainActivity
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerLifecycleTest {
    @Test
    fun nativeServerSurvivesBackgroundAndRecoversFromPanelDeath() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        SystemClock.sleep(1000)
        try {
            XuiService.dispatch(context, XuiService.ACTION_START)
            awaitPhase(ServerPhase.RUNNING)
            val panel = URL("http://127.0.0.1:2053/").openConnection() as HttpURLConnection
            try {
                assertEquals(200, panel.responseCode)
                val html = panel.inputStream.bufferedReader().use { it.readText() }
                assertTrue(
                    "Embedded frontend must contain built JavaScript assets",
                    html.contains("/assets/")
                )
            } finally {
                panel.disconnect()
            }
            val directory = context.applicationInfo.nativeLibraryDir
            assertTrue(
                "Xray child must execute from nativeLibraryDir",
                ownedPid("$directory/libxray.so") != null
            )
            assertTrue(File(context.filesDir, "server/db/x-ui.db").isFile)
            PanelManagementProbe().verifyLoginAndVlessInbound(context)

            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            SystemClock.sleep(2000)
            assertEquals(ServerPhase.RUNNING, ServerStore.state.value.phase)
            assertTrue("Panel must survive background", isPanelReachable())

            // 重複啟動指令仍須保留異常結束監控。
            XuiService.dispatch(context, XuiService.ACTION_START)
            SystemClock.sleep(500)
            val oldPanel = requireNotNull(ownedPid("$directory/libxui.so"))
            Os.kill(oldPanel, OsConstants.SIGKILL)
            awaitPhase(ServerPhase.ERROR)
            awaitNoProcess("$directory/libxray.so")

            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            SystemClock.sleep(1000)
            XuiService.dispatch(context, XuiService.ACTION_START)
            awaitPhase(ServerPhase.RUNNING)
            assertTrue("New panel must bind after crash recovery", isPanelReachable())
            assertTrue(ownedPid("$directory/libxui.so") != oldPanel)

            val beforeRestart = ownedPid("$directory/libxui.so")
            XuiService.dispatch(context, XuiService.ACTION_RESTART)
            awaitNewPanel("$directory/libxui.so", beforeRestart)
            assertTrue(File(context.filesDir, "server/db/x-ui.db").isFile)
        } finally {
            XuiService.dispatch(context, XuiService.ACTION_STOP)
            awaitPhase(ServerPhase.STOPPED)
        }
        assertTrue("Stopping must release panel port", !isPanelReachable())
        awaitNoProcess("${context.applicationInfo.nativeLibraryDir}/libxray.so")
    }

    private fun awaitNewPanel(path: String, previous: Int?) {
        val deadline = SystemClock.elapsedRealtime() + 60000
        while (SystemClock.elapsedRealtime() < deadline) {
            val current = ownedPid(path)
            if (current != null && current != previous && ServerStore.state.value.phase == ServerPhase.RUNNING) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Restart did not create a new ready panel")
    }

    private fun awaitPhase(phase: ServerPhase) {
        val deadline = SystemClock.elapsedRealtime() + 60000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ServerStore.state.value.phase == phase) return
            SystemClock.sleep(100)
        }
        assertEquals("Timed out waiting for service phase", phase, ServerStore.state.value.phase)
    }

    private fun awaitNoProcess(path: String) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ownedPid(path) == null) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Owned core process survived shutdown")
    }

    private fun ownedPid(path: String): Int? = File("/proc").listFiles()?.firstNotNullOfOrNull { directory ->
        val pid = directory.name.toIntOrNull() ?: return@firstNotNullOfOrNull null
        runCatching {
            if (Os.stat(directory.path).st_uid == android.os.Process.myUid() &&
                Os.readlink(File(directory, "exe").path) == path
            ) {
                pid
            } else {
                null
            }
        }.getOrNull()
    }

    private fun isPanelReachable(): Boolean = runCatching {
        val connection = URL("http://127.0.0.1:2053/").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 500
            connection.readTimeout = 500
            connection.responseCode == 200
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)
}
