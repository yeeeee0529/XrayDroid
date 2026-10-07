package io.github.xraydroid.runtime

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal object FrpInstanceCatalog {
    private const val VERSION = 1

    fun isValidId(id: String): Boolean = id == FrpStore.DEFAULT_INSTANCE_ID ||
        runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

    fun isValidName(name: String): Boolean = name.isNotBlank() && name.length <= 100

    fun root(filesDir: File, id: String): File {
        require(isValidId(id)) { "Invalid FRP instance identifier" }
        return File(filesDir, if (id == FrpStore.DEFAULT_INSTANCE_ID) "server/frp" else "server/frp/instances/$id")
    }

    fun read(input: InputStream): List<FrpInstance> {
        val data = DataInputStream(input)
        check(data.readInt() == VERSION) { "Unsupported FRP catalog version" }
        val count = data.readInt()
        check(count > 0) { "Invalid FRP instance count" }
        val instances = buildList {
            repeat(count) { add(FrpInstance(data.readUTF(), data.readUTF())) }
        }
        validate(instances)
        check(data.read() == -1) { "Unexpected FRP catalog data" }
        return instances
    }

    fun write(output: OutputStream, instances: List<FrpInstance>) {
        validate(instances)
        val data = DataOutputStream(output)
        data.writeInt(VERSION)
        data.writeInt(instances.size)
        instances.forEach {
            data.writeUTF(it.id)
            data.writeUTF(it.name)
        }
        data.flush()
    }

    private fun validate(instances: List<FrpInstance>) {
        check(instances.isNotEmpty()) { "Invalid FRP instance count" }
        check(instances.all { isValidId(it.id) && isValidName(it.name) }) { "Invalid FRP instance metadata" }
        check(instances.map { it.id }.distinct().size == instances.size) { "Duplicate FRP instance identifier" }
        check(instances.any { it.id == FrpStore.DEFAULT_INSTANCE_ID }) { "Default FRP instance is missing" }
    }
}
