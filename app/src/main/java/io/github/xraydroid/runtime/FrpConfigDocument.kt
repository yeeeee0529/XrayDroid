package io.github.xraydroid.runtime

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable

class FrpConfigDocument private constructor(
    val values: Map<String, Any>,
    originalSource: String?,
    private val ruleIds: Map<String, List<Long>>
) {
    constructor(values: Map<String, Any>, source: String) : this(stringMap(values), source, emptyMap())

    private val sourceCache = lazy { originalSource ?: buildString { appendTable(values, emptyList()) } }
    val source: String get() = sourceCache.value
    internal val isSourceMaterialized: Boolean get() = sourceCache.isInitialized()
    private val identities = lazy {
        listOf("proxies", "visitors").associateWith { kind ->
            ruleIds[kind] ?: (values[kind] as? List<*>).orEmpty().map { nextRuleId.incrementAndGet() }
        }
    }

    fun ruleId(kind: String, index: Int): Long {
        requireRuleKind(kind)
        return identities.value.getValue(kind)[index]
    }

    operator fun component1(): Map<String, Any> = values
    operator fun component2(): String = source
    fun copy(values: Map<String, Any> = this.values, source: String = this.source): FrpConfigDocument = FrpConfigDocument(values, source)

    override fun equals(other: Any?): Boolean =
        this === other || other is FrpConfigDocument && values == other.values && source == other.source
    override fun hashCode(): Int = 31 * values.hashCode() + source.hashCode()

    fun get(path: String): Any? = readPath(values, keyPath(path))

    fun set(path: String, value: Any?): FrpConfigDocument = changed(writePath(values, keyPath(path), value))

    fun rules(kind: String): List<Map<String, Any>> {
        requireRuleKind(kind)
        val entries = values[kind] ?: return emptyList()
        require(entries is List<*> && entries.all { it is Map<*, *> }) { "Invalid rule list." }
        // 建構與寫入時已正規化所有巢狀值，可共用未修改的規則而不再深層複製。
        @Suppress("UNCHECKED_CAST")
        return entries as List<Map<String, Any>>
    }

    fun updateRule(kind: String, index: Int, path: String, value: Any?): FrpConfigDocument {
        val entries = rules(kind).toMutableList()
        require(index in entries.indices) { "Invalid rule index." }
        entries[index] = writePath(entries[index], keyPath(path), value)
        return changed(values + (kind to entries))
    }

    fun addRule(kind: String, type: String): FrpConfigDocument {
        val entries = rules(kind)
        val supported = if (kind == "visitors") visitorTypes else proxyTypes
        require(type in supported) { "Invalid rule type." }
        val names = (rules("proxies") + rules("visitors")).map { it["name"] }.toSet()
        val prefix = if (kind == "visitors") "$type-visitor" else type
        val name = generateSequence(1) { it + 1 }.map { "$prefix-$it" }.first { it !in names }
        val rule = linkedMapOf<String, Any>("name" to name, "type" to type)
        if (kind == "visitors") {
            rule["serverName"] = ""
            rule["secretKey"] = ""
            rule["bindAddr"] = "127.0.0.1"
            rule["bindPort"] = 6000L
        } else {
            rule["localIP"] = "127.0.0.1"
            rule["localPort"] = 8080L
            when (type) {
                "tcp", "udp" -> rule["remotePort"] = 6000L
                "http", "https" -> rule["customDomains"] = listOf("example.com")
                "tcpmux" -> {
                    rule["multiplexer"] = "httpconnect"
                    rule["customDomains"] = listOf("example.com")
                }
                "stcp", "sudp", "xtcp" -> rule["secretKey"] = ""
            }
        }
        return changed(
            values + (kind to (entries + rule)),
            identities.value + (kind to (identities.value.getValue(kind) + nextRuleId.incrementAndGet()))
        )
    }

    fun removeRule(kind: String, index: Int): FrpConfigDocument {
        val entries = rules(kind).toMutableList()
        require(index in entries.indices) { "Invalid rule index." }
        entries.removeAt(index)
        val updated = if (entries.isEmpty()) values - kind else values + (kind to entries)
        return changed(
            updated,
            identities.value + (kind to identities.value.getValue(kind).filterIndexed { position, _ -> position != index })
        )
    }

    private fun changed(updated: Map<String, Any>, ids: Map<String, List<Long>>? = null): FrpConfigDocument {
        if (updated == values) return this
        val retainedIds = listOf("proxies", "visitors").associateWith { kind ->
            val entries = updated[kind] as? List<*>
            val previous = (ids ?: identities.value)[kind].orEmpty()
            if (entries?.size == previous.size) previous else entries.orEmpty().map { nextRuleId.incrementAndGet() }
        }
        return FrpConfigDocument(updated, null, retainedIds)
    }

    companion object {
        private val nextRuleId = AtomicLong()
        val proxyTypes = listOf("tcp", "udp", "http", "https", "tcpmux", "stcp", "sudp", "xtcp")
        val visitorTypes = listOf("stcp", "sudp", "xtcp")

        fun parse(text: String): FrpConfigDocument {
            require(text.length <= 1024 * 1024 && text.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) {
                "Configuration exceeds size limit."
            }
            try {
                val parsed = Toml.parse(text)
                require(!parsed.hasErrors()) { "Invalid TOML configuration." }
                return FrpConfigDocument(readTable(parsed), text, emptyMap())
            } catch (_: RuntimeException) {
                throw IllegalArgumentException("Invalid TOML configuration.")
            } catch (_: StackOverflowError) {
                throw IllegalArgumentException("TOML configuration exceeds nesting limit.")
            }
        }

        private fun requireRuleKind(kind: String) {
            require(kind == "proxies" || kind == "visitors") { "Invalid rule kind." }
        }

        private fun keyPath(path: String): List<String> = try {
            Toml.parseDottedKey(path)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("Invalid configuration key.")
        }

        private fun readTable(table: TomlTable): Map<String, Any> = table.keySet().associateWith { key ->
            normalize(requireNotNull(table.get(listOf(key))))
        }

        private fun stringMap(map: Map<*, *>): Map<String, Any> = map.entries.associate { (key, value) ->
            require(key is String && value != null) { "Invalid configuration value." }
            key to normalize(value)
        }

        private fun normalize(value: Any): Any = when (value) {
            is TomlTable -> readTable(value)
            is TomlArray -> (0 until value.size()).map { normalize(value.get(it)) }
            is Map<*, *> -> stringMap(value)
            is List<*> -> value.map { normalize(requireNotNull(it) { "Invalid configuration value." }) }
            is Byte, is Short, is Int -> (value as Number).toLong()
            is Float -> value.toDouble()
            is String, is Long, is Double, is Boolean, is OffsetDateTime, is LocalDateTime, is LocalDate, is LocalTime -> value
            else -> throw IllegalArgumentException("Unsupported configuration value.")
        }

        private fun readPath(map: Map<String, Any>, path: List<String>): Any? {
            var current: Any? = map
            path.forEach { key -> current = (current as? Map<*, *>)?.get(key) }
            return current
        }

        private fun writePath(map: Map<String, Any>, path: List<String>, value: Any?): Map<String, Any> {
            require(path.isNotEmpty()) { "Invalid configuration key." }
            val key = path.first()
            val updated = map.toMutableMap()
            if (path.size == 1) {
                if (value == null) updated.remove(key) else updated[key] = normalize(value)
            } else {
                val existing = map[key]
                require(existing == null || existing is Map<*, *>) { "Configuration key conflicts with an existing value." }
                if (value == null && existing == null) return map
                // 巢狀容器只會來自已正規化的 values，保留其他分支的參照。
                @Suppress("UNCHECKED_CAST")
                val nested = writePath(existing as? Map<String, Any> ?: emptyMap(), path.drop(1), value)
                if (nested.isEmpty()) updated.remove(key) else updated[key] = nested
            }
            return updated
        }

        private fun StringBuilder.appendTable(map: Map<String, Any>, path: List<String>) {
            map.filterValues { it !is Map<*, *> && !isTableArray(it) }.forEach { (key, value) ->
                append(quoted(key)).append(" = ").append(literal(value)).append('\n')
            }
            map.forEach { (key, value) ->
                val nestedPath = path + key
                when {
                    value is Map<*, *> -> {
                        append('\n').append('[').append(tablePath(nestedPath)).append("]\n")
                        appendTable(stringMap(value), nestedPath)
                    }
                    isTableArray(value) -> (value as List<*>).forEach { entry ->
                        append('\n').append("[[").append(tablePath(nestedPath)).append("]]\n")
                        appendTable(stringMap(entry as Map<*, *>), nestedPath)
                    }
                }
            }
        }

        private fun isTableArray(value: Any): Boolean = value is List<*> && value.isNotEmpty() && value.all { it is Map<*, *> }

        private fun tablePath(path: List<String>): String = path.joinToString(".") { quoted(it) }

        private fun quoted(value: String): String = "\"${Toml.tomlEscape(value)}\""

        private fun literal(value: Any): String = when (value) {
            is String -> quoted(value)
            is Boolean, is Long -> value.toString()
            is Double -> when {
                value.isNaN() -> "nan"
                value == Double.POSITIVE_INFINITY -> "inf"
                value == Double.NEGATIVE_INFINITY -> "-inf"
                else -> value.toString()
            }
            is OffsetDateTime -> value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            is LocalDateTime -> value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            is LocalDate -> value.format(DateTimeFormatter.ISO_LOCAL_DATE)
            is LocalTime -> value.format(DateTimeFormatter.ISO_LOCAL_TIME)
            is List<*> -> value.joinToString(", ", "[", "]") { literal(requireNotNull(it)) }
            is Map<*, *> -> stringMap(value).entries.joinToString(", ", "{", "}") { (key, entry) -> "${quoted(key)} = ${literal(entry)}" }
            else -> throw IllegalArgumentException("Unsupported configuration value.")
        }
    }
}
