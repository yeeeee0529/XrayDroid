package io.github.xraydroid.runtime

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.core.content.edit
import io.github.xraydroid.R
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class FrpPhase { STOPPED, VALIDATING, STARTING, RUNNING, STOPPING, ERROR }

data class FrpProxyStatus(val name: String, val type: String, val status: String, val remoteAddress: String)

data class FrpState(
    val phase: FrpPhase = FrpPhase.STOPPED,
    val message: TextResource? = TextResource(R.string.frp_message_idle),
    val config: String = "",
    val loaded: Boolean = false,
    val revision: Long = 0,
    val allowUnsafeTokenCommand: Boolean = false,
    val connection: FrpConnectionStatus = FrpConnectionStatus(),
    val proxies: List<FrpProxyStatus> = emptyList()
)

object FrpStore {
    const val MAX_CONFIG_BYTES = 1024 * 1024
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
                    val layout = FrpLayout(app)
                    val file = AtomicFile(layout.config)
                    val text = if (layout.config.exists() || File(layout.config.path + ".bak").exists()) {
                        file.openRead().use { input ->
                            val bytes = input.readBytes()
                            check(bytes.size <= MAX_CONFIG_BYTES) { "FRP configuration exceeds size limit" }
                            bytes.toString(Charsets.UTF_8)
                        }
                    } else {
                        ""
                    }
                    val allowUnsafe = app.getSharedPreferences("frp", Context.MODE_PRIVATE).getBoolean("allow_token_command", false)
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
        if (state.value.phase in setOf(FrpPhase.RUNNING, FrpPhase.STARTING, FrpPhase.STOPPING)) return
        context.getSharedPreferences("frp", Context.MODE_PRIVATE).edit { putBoolean("allow_token_command", enabled) }
        mutableState.update { it.copy(allowUnsafeTokenCommand = enabled) }
    }

    suspend fun saveConfig(context: Context, text: String): Boolean = configMutex.withLock {
        if (!validate(context, text)) return@withLock false
        runCatching {
            val layout = FrpLayout(context)
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
        if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size > MAX_CONFIG_BYTES) {
            message(TextResource(R.string.frp_message_config_invalid))
            return false
        }
        return runCatching {
            val layout = FrpLayout(context)
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
            val layout = FrpLayout(context)
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

    internal fun transition(phase: FrpPhase, message: TextResource?) {
        mutableState.update { it.copy(phase = phase, message = message, connection = FrpConnectionStatus(), proxies = emptyList()) }
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
