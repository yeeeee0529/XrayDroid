package io.github.xraydroid.runtime

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrpConfigImportTest {
    @Test
    fun importedIncludesRemainPrivateAndInvalidSavesPreserveSettings() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "io.github.xraydroid.validation") {
            "FRP import verification requires the isolated validation application ID"
        }
        FrpStore.initialize(context)
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!FrpStore.state.value.loaded && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue("FRP settings must finish loading", FrpStore.state.value.loaded)
        val originalConfig = FrpStore.state.value.config
        val layout = FrpLayout(context)
        val baseline = "serverAddr = \"127.0.0.1\"\nserverPort = 17000\n"
        val source = File.createTempFile("frp-import-fixture-", ".toml", context.cacheDir)
        val included = """
            [[proxies]]
            name = "imported-tcp"
            type = "tcp"
            localIP = "127.0.0.1"
            localPort = 17001
            remotePort = 17002
        """.trimIndent()
        source.writeText(included)
        var importedFile: File? = null
        try {
            assertTrue("A standalone baseline must save", FrpStore.saveConfig(context, baseline))
            val importedPath = FrpStore.importSupportFile(context, Uri.fromFile(source))
            assertNotNull("A local TOML support file must import", importedPath)
            val path = checkNotNull(importedPath)
            assertTrue("The import must retain the TOML suffix", path.endsWith(".toml"))
            assertTrue("The returned path must be relative to the private support directory", path.startsWith("support/"))
            val copied = File(layout.root, path)
            importedFile = copied
            assertEquals(
                "The imported basename must be a generated UUID",
                copied.nameWithoutExtension,
                UUID.fromString(copied.nameWithoutExtension).toString()
            )
            assertNotEquals("The user filename must not become the private filename", source.name, copied.name)
            assertEquals(
                "The destination must be inside the private runtime directory",
                File(layout.root, "support").canonicalFile,
                copied.canonicalFile.parentFile
            )
            assertTrue(
                "The runtime directory must remain under private application files",
                copied.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator)
            )
            assertEquals("Import must preserve file contents", included, copied.readText())
            assertTrue("Import must preserve the source", source.isFile)
            assertEquals("Import must not modify the source", included, source.readText())

            val config = baseline + "includes = [\"$path\"]\n"
            assertTrue("The official verifier must resolve imported TOML includes", FrpStore.validateConfig(context, config))
            copied.writeText(included.replace("localPort = 17001", "localPort = \"invalid\""))
            assertFalse("Included files must actually be parsed rather than silently ignored", FrpStore.validateConfig(context, config))
            copied.writeText(included)
            assertTrue("A complete configuration with imported includes must save", FrpStore.saveConfig(context, config))
            val revision = FrpStore.state.value.revision
            assertFalse("Malformed settings must fail verification", FrpStore.validateConfig(context, "serverPort = ["))
            assertFalse("Invalid settings must not save", FrpStore.saveConfig(context, "serverPort = \"invalid\""))
            assertEquals("An invalid save must retain the in-memory configuration", config, FrpStore.state.value.config)
            assertEquals("An invalid save must retain the disk configuration", config, layout.config.readText())
            assertEquals("An invalid save must retain the revision", revision, FrpStore.state.value.revision)
            assertTrue("Verification must preserve the imported support file", copied.isFile)
            assertEquals("Verification must preserve the original support contents", included, source.readText())
        } finally {
            // 首次驗證沒有原始設定時，保留不依賴測試附件的有效基準設定。
            try {
                assertTrue(
                    "Cleanup must restore a standalone configuration",
                    FrpStore.saveConfig(context, originalConfig.ifBlank { baseline })
                )
            } finally {
                importedFile?.delete()
                source.delete()
            }
        }
    }
}
