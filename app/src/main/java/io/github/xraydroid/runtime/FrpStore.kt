package io.github.xraydroid.runtime

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.core.content.edit
import io.github.xraydroid.R
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class FrpPhase { STOPPED, VALIDATING, STARTING, RUNNING, WAITING_FOR_NETWORK, STOPPING, ERROR }

data class FrpProxyStatus(val name: String, val type: String, val status: String, val remoteAddress: String)

data class FrpState(
    val phase: FrpPhase = FrpPhase.STOPPED,
    val message: TextResource? = null,
    val config: String = "",
    val loaded: Boolean = false,
    val revision: Long = 0,
    val allowUnsafeTokenCommand: Boolean = false,
    val connection: FrpConnectionStatus = FrpConnectionStatus(),
    val proxies: List<FrpProxyStatus> = emptyList(),
    val outboundNetworkLabel: TextResource? = null
)

class FrpInstanceStore internal constructor(val instanceId: String) {
    private val mutableState = MutableStateFlow(FrpState())
    val state = mutableState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configMutex = Mutex()

    @Volatile private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        scope.launch {
            configMutex.withLock {
                runCatching {
                    val layout = FrpLayout(app, instanceId)
                    val file = AtomicFile(layout.config)
                    val text = if (layout.config.exists() || File(layout.config.path + ".bak").exists()) {
                        file.openRead().use { input ->
                            val bytes = input.readBytes()
                            check(bytes.size <= FrpStore.MAX_CONFIG_BYTES) { "FRP configuration exceeds size limit" }
                            bytes.toString(Charsets.UTF_8)
                        }
                    } else {
                        ""
                    }
                    val allowUnsafe = app.getSharedPreferences(
                        FrpStore.preferencesName(instanceId),
                        Context.MODE_PRIVATE
                    ).getBoolean("allow_token_command", false)
                    mutableState.update { it.copy(config = text, loaded = true, allowUnsafeTokenCommand = allowUnsafe) }
                }.onFailure {
                    mutableState.update { it.copy(loaded = true, message = TextResource(R.string.frp_message_config_unreadable)) }
                }
            }
        }
    }

    suspend fun validateConfig(context: Context, text: String): Boolean = configMutex.withLock {
        validate(context, text)
    }

    fun setAllowUnsafeTokenCommand(context: Context, enabled: Boolean) {
        if (state.value.phase in setOf(FrpPhase.RUNNING, FrpPhase.STARTING, FrpPhase.WAITING_FOR_NETWORK, FrpPhase.STOPPING)) return
        context.getSharedPreferences(
            FrpStore.preferencesName(instanceId),
            Context.MODE_PRIVATE
        ).edit { putBoolean("allow_token_command", enabled) }
        mutableState.update { it.copy(allowUnsafeTokenCommand = enabled) }
    }

    suspend fun saveConfig(context: Context, text: String): Boolean = configMutex.withLock {
        if (!validate(context, text)) return@withLock false
        runCatching {
            val layout = FrpLayout(context, instanceId)
            val file = AtomicFile(layout.config)
            val output = file.startWrite()
            try {
                output.write(text.toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
            mutableState.update {
                it.copy(
                    config = text,
                    loaded = true,
                    revision = it.revision + 1,
                    message = TextResource(
                        if (it.phase == FrpPhase.RUNNING) R.string.frp_message_saved_running else R.string.frp_message_saved
                    )
                )
            }
        }.fold(onSuccess = { true }, onFailure = {
            message(TextResource(R.string.frp_message_save_failed))
            false
        })
    }

    private fun validate(context: Context, text: String): Boolean {
        if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size > FrpStore.MAX_CONFIG_BYTES) {
            message(TextResource(R.string.frp_message_config_invalid))
            return false
        }
        return runCatching {
            val layout = FrpLayout(context, instanceId)
            layout.prepare()
            val candidate = File.createTempFile("verify-", ".toml", layout.root)
            try {
                candidate.writeText(text)
                layout.verify(candidate, state.value.allowUnsafeTokenCommand)
            } finally {
                candidate.delete()
            }
        }.fold(onSuccess = {
            message(TextResource(if (it) R.string.frp_message_valid else R.string.frp_message_invalid))
            it
        }, onFailure = {
            message(TextResource(R.string.frp_message_verify_failed))
            false
        })
    }

    suspend fun importSupportFile(context: Context, uri: Uri): String? = configMutex.withLock {
        runCatching {
            val layout = FrpLayout(context, instanceId)
            layout.prepare()
            val directory = File(layout.root, "support").apply { check(isDirectory || mkdirs()) }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment.orEmpty()
            val extension = name.substringAfterLast('.', "").lowercase()
                .takeIf { it in setOf("toml", "yaml", "yml", "json", "pem", "crt", "cer", "key", "txt") } ?: "dat"
            val destination = File(directory, "${UUID.randomUUID()}.$extension")
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    checkNotNull(input) { "Unable to open support file" }
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            check(total <= 16 * 1024 * 1024) { "Support file exceeds size limit" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                "support/${destination.name}"
            } catch (error: Exception) {
                destination.delete()
                throw error
            }
        }.getOrElse {
            message(TextResource(R.string.frp_message_import_failed))
            null
        }
    }

    internal fun transition(phase: FrpPhase, message: TextResource?, outboundNetworkLabel: TextResource? = null) {
        mutableState.update {
            it.copy(
                phase = phase,
                message = message,
                connection = FrpConnectionStatus(),
                proxies = emptyList(),
                outboundNetworkLabel = outboundNetworkLabel
            )
        }
    }

    internal fun updateRuntime(status: FrpRuntimeStatus?) {
        mutableState.update {
            // 停止與輪詢可能交錯；停止後不可被晚到的結果標示為已連線。
            if (it.phase == FrpPhase.RUNNING) {
                it.copy(connection = status?.connection ?: FrpConnectionStatus(), proxies = status?.proxies.orEmpty())
            } else {
                it
            }
        }
    }

    internal fun message(message: TextResource) {
        mutableState.update { it.copy(message = message) }
    }
}

data class FrpInstance(val id: String, val name: String)

object FrpStore {
    const val DEFAULT_INSTANCE_ID = "default"
    const val MAX_CONFIG_BYTES = 1024 * 1024
    private val stores = ConcurrentHashMap<String, FrpInstanceStore>()
    private val mutableInstances = MutableStateFlow<List<FrpInstance>>(emptyList())
    val instances = mutableInstances.asStateFlow()
    private val mutableInstancesLoaded = MutableStateFlow(false)
    val instancesLoaded = mutableInstancesLoaded.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val catalogMutex = Mutex()
    private var catalogWritable = true
    val state get() = forInstance(DEFAULT_INSTANCE_ID).state

    fun forInstance(id: String): FrpInstanceStore {
        require(FrpInstanceCatalog.isValidId(id)) { "Invalid FRP instance identifier" }
        return stores.getOrPut(id) { FrpInstanceStore(id) }
    }

    internal fun preferencesName(id: String): String = if (id == DEFAULT_INSTANCE_ID) "frp" else "frp_instance_$id"

    fun initialize(context: Context) {
        forInstance(DEFAULT_INSTANCE_ID).initialize(context)
        val app = context.applicationContext
        scope.launch { catalogMutex.withLock { loadCatalog(app) } }
    }

    private fun catalogFile(context: Context) = AtomicFile(File(context.filesDir, "server/frp/instances.bin"))

    private fun loadCatalog(context: Context) {
        if (mutableInstancesLoaded.value) return
        val file = catalogFile(context)
        runCatching {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                file.openRead().use { FrpInstanceCatalog.read(it) }
            } else {
                listOf(FrpInstance(DEFAULT_INSTANCE_ID, context.getString(R.string.frp_instance_default_name)))
            }
        }.onSuccess { mutableInstances.value = it }.onFailure {
            // 目錄損毀時保留檔案；禁止用新的清單覆寫既有實例資料。
            catalogWritable = false
            mutableInstances.value = listOf(FrpInstance(DEFAULT_INSTANCE_ID, context.getString(R.string.frp_instance_default_name)))
            message(TextResource(R.string.frp_message_config_unreadable))
        }
        mutableInstances.value.forEach { forInstance(it.id).initialize(context) }
        mutableInstancesLoaded.value = true
    }

    private fun saveCatalog(context: Context, instances: List<FrpInstance>): Boolean = runCatching {
        val file = catalogFile(context)
        check(file.baseFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Unable to create FRP catalog directory" }
        val output = file.startWrite()
        try {
            FrpInstanceCatalog.write(output, instances)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        mutableInstances.value = instances
        true
    }.getOrElse {
        message(TextResource(R.string.frp_message_save_failed))
        false
    }

    suspend fun createInstance(context: Context, name: String): String? = catalogMutex.withLock {
        loadCatalog(context)
        val normalized = name.trim()
        if (!catalogWritable || !FrpInstanceCatalog.isValidName(normalized)) return@withLock null
        val instance = FrpInstance(UUID.randomUUID().toString(), normalized)
        if (!saveCatalog(context, instances.value + instance)) return@withLock null
        forInstance(instance.id).initialize(context)
        instance.id
    }

    suspend fun renameInstance(context: Context, id: String, name: String): Boolean = catalogMutex.withLock {
        loadCatalog(context)
        val normalized = name.trim()
        if (!catalogWritable || !FrpInstanceCatalog.isValidName(normalized) || instances.value.none { it.id == id }) return@withLock false
        saveCatalog(context, instances.value.map { if (it.id == id) it.copy(name = normalized) else it })
    }

    suspend fun validateConfig(context: Context, text: String) = forInstance(DEFAULT_INSTANCE_ID).validateConfig(context, text)
    suspend fun saveConfig(context: Context, text: String) = forInstance(DEFAULT_INSTANCE_ID).saveConfig(context, text)
    suspend fun importSupportFile(context: Context, uri: Uri) = forInstance(DEFAULT_INSTANCE_ID).importSupportFile(context, uri)
    fun setAllowUnsafeTokenCommand(context: Context, enabled: Boolean) =
        forInstance(DEFAULT_INSTANCE_ID).setAllowUnsafeTokenCommand(context, enabled)
    internal fun transition(phase: FrpPhase, message: TextResource?, outboundNetworkLabel: TextResource? = null) =
        forInstance(DEFAULT_INSTANCE_ID).transition(phase, message, outboundNetworkLabel)
    internal fun updateRuntime(status: FrpRuntimeStatus?) = forInstance(DEFAULT_INSTANCE_ID).updateRuntime(status)
    internal fun message(message: TextResource) = forInstance(DEFAULT_INSTANCE_ID).message(message)
}
