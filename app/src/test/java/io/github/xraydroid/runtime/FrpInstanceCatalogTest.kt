package io.github.xraydroid.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrpInstanceCatalogTest {
    private val firstId = "12345678-1234-1234-1234-123456789abc"
    private val secondId = "12345678-1234-1234-1234-123456789abd"

    @Test
    fun catalogRoundTripPreservesNamesAndOrder() {
        val instances = listOf(FrpInstance("default", "預設實例"), FrpInstance(firstId, "台北"), FrpInstance(secondId, "Tokyo"))
        val output = ByteArrayOutputStream()
        FrpInstanceCatalog.write(output, instances)
        assertEquals(instances, FrpInstanceCatalog.read(ByteArrayInputStream(output.toByteArray())))
    }

    @Test
    fun defaultRootPreservesLegacyFilesAndNewRootsAreIsolated() {
        val files = File("/test-files")
        assertEquals(File(files, "server/frp"), FrpInstanceCatalog.root(files, "default"))
        assertEquals(File(files, "server/frp/instances/$firstId"), FrpInstanceCatalog.root(files, firstId))
        assertNotEquals(FrpInstanceCatalog.root(files, firstId), FrpInstanceCatalog.root(files, secondId))
        assertEquals("frp", FrpStore.preferencesName("default"))
        assertNotEquals(FrpStore.preferencesName(firstId), FrpStore.preferencesName(secondId))
    }

    @Test
    fun invalidIdsCannotEscapeInstanceRoot() {
        listOf("../other", "", "DEFAULT", "1234", "$firstId/../other").forEach {
            assertFalse(FrpInstanceCatalog.isValidId(it))
            assertThrows(IllegalArgumentException::class.java) { FrpInstanceCatalog.root(File("/test-files"), it) }
        }
    }

    @Test
    fun invalidCatalogCannotReplaceExistingInstances() {
        val default = FrpInstance("default", "預設")
        listOf(emptyList(), listOf(FrpInstance(firstId, "Only")), listOf(default, default), listOf(default, FrpInstance(firstId, " ")))
            .forEach { instances ->
                assertThrows(IllegalStateException::class.java) { FrpInstanceCatalog.write(ByteArrayOutputStream(), instances) }
            }
        val output = ByteArrayOutputStream()
        FrpInstanceCatalog.write(output, listOf(default))
        assertThrows(IllegalStateException::class.java) {
            FrpInstanceCatalog.read(ByteArrayInputStream(output.toByteArray() + byteArrayOf(1)))
        }
    }
}
