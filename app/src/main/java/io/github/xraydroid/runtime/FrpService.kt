package io.github.xraydroid.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.system.OsConstants
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import io.github.xraydroid.MainActivity
import io.github.xraydroid.R
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class FrpService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private val commandMutex = Mutex()
    private val runtimes = ConcurrentHashMap<String, Runtime>()
    private val desiredPreferences by lazy { getSharedPreferences("frp_runtime", Context.MODE_PRIVATE) }
    private var activeStartId = 0
    private var pendingCommands = 0

    @Volatile private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        FrpStore.initialize(applicationContext)
        FrpNetworkStore.initialize(applicationContext)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.frp_notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(lifecycleLock) {
            activeStartId = startId
            pendingCommands++
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            commandMutex.withLock {
                FrpStore.instancesLoaded.first { it }
                withContext(Dispatchers.Main) {
                    synchronized(lifecycleLock) {
                        pendingCommands--
                        val ids = if (intent == null) {
                            // 系統重建只恢復先前明確要求執行的實例。
                            desiredPreferences.getStringSet("desired_instances", emptySet()).orEmpty().toSet()
                        } else {
                            setOf(intent.getStringExtra(EXTRA_INSTANCE_ID) ?: FrpStore.DEFAULT_INSTANCE_ID)
                        }
                        val validIds = ids.filter { id -> FrpStore.instances.value.any { it.id == id } }
                        validIds.forEach { id ->
                            runtimes.getOrPut(id) { Runtime(id) }.dispatch(intent?.action ?: ACTION_START)
                        }
                        persistDesired()
                        if (validIds.isEmpty() && runtimes.values.none { it.desiredRunning || it.process != null }) {
                            scope.launch(Dispatchers.Main) {
                                synchronized(lifecycleLock) {
                                    if (activeStartId == startId && runtimes.values.none { it.desiredRunning || it.process != null }) {
                                        stopForeground(STOP_FOREGROUND_REMOVE)
                                        stopSelfResult(startId)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun persistDesired() = synchronized(lifecycleLock) {
        if (destroyed) return@synchronized
        desiredPreferences.edit(commit = true) {
            putStringSet("desired_instances", runtimes.values.filter { it.desiredRunning }.map { it.id }.toSet())
        }
    }

    private inner class Runtime(val id: String) {
        private val mutex = Mutex()
        private val processLock = Any()
        private val generation = AtomicLong()
        private val layout = FrpLayout(this@FrpService, id)
        private val store = FrpStore.forInstance(id)
        private val networkStore = FrpNetworkStore.acquire(this@FrpService, id)

        @Volatile var process: java.lang.Process? = null

        @Volatile var desiredRunning = false

        @Volatile private var processGroup: Int? = null
        private var adminPort = 0
        private var adminAuthorization = ""

        private val networkObserver = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            networkStore.state.map { state ->
                Triple(state.selectedMode, state.selectedInterfaceName, if (state.followsSystem()) 0L else state.selectedOption?.handle)
            }.distinctUntilChanged().drop(1).collect {
                val ticket = synchronized(processLock) {
                    if (!desiredRunning) return@collect
                    generation.incrementAndGet()
                }
                scope.launch {
                    mutex.withLock {
                        if (generation.get() == ticket && desiredRunning) performAction(ticket, restart = true)
                    }
                }
            }
        }

        fun dispatch(action: String) {
            val ticket = synchronized(processLock) {
                desiredRunning = action != ACTION_STOP
                generation.incrementAndGet()
            }
            scope.launch {
                mutex.withLock {
                    store.state.first { it.loaded }
                    if (generation.get() == ticket) performAction(ticket, restart = action == ACTION_RESTART)
                }
            }
        }

        fun destroy() {
            synchronized(processLock) {
                generation.incrementAndGet()
                OwnedProcesses.terminateGroup(
                    processGroup ?: OwnedProcesses.groupFor(layout.executable.absolutePath, layout.config.absolutePath),
                    OsConstants.SIGKILL
                )
                process?.destroyForcibly()
                process = null
                processGroup = null
                desiredRunning = false
            }
            dispose()
            if (store.state.value.phase != FrpPhase.ERROR) store.transition(FrpPhase.STOPPED, TextResource(R.string.frp_message_stopped))
        }
        fun dispose() {
            networkObserver.cancel()
            FrpNetworkStore.release(id)
        }

        private suspend fun performAction(ticket: Long, restart: Boolean) {
            try {
                if (!desiredRunning) {
                    stopClient()
                } else {
                    if (restart) stopClient()
                    startClient(ticket)
                }
            } catch (error: CancellationException) {
                if (destroyed) throw error
                stopClient()
            } catch (error: Exception) {
                if (destroyed) throw CancellationException("FRP service destroyed", error)
                stopClient()
                val waiting = synchronized(processLock) {
                    if (generation.get() != ticket) return@synchronized false
                    val latest = networkStore.state.value
                    if (desiredRunning && !latest.followsSystem() && latest.selectedOption == null) {
                        true
                    } else {
                        desiredRunning = false
                        store.transition(FrpPhase.ERROR, TextResource(R.string.frp_message_start_failed))
                        false
                    }
                }
                if (waiting) waitForNetwork()
            }
            persistDesired()
            if (generation.get() == ticket && process == null && store.state.value.phase != FrpPhase.WAITING_FOR_NETWORK) {
                finish(ticket)
            }
        }

        private fun waitForNetwork() {
            store.transition(FrpPhase.WAITING_FOR_NETWORK, TextResource(R.string.frp_message_network_unavailable))
            updateNotification()
        }

        private fun checkActive(ticket: Long) {
            if (destroyed || generation.get() != ticket || !desiredRunning) throw CancellationException()
        }

        private suspend fun startClient(ticket: Long) {
            checkActive(ticket)
            val existing = process
            if (existing?.isAlive == true) {
                monitor(existing, ticket)
                monitorStatus(existing, ticket)
                updateNotification()
                return
            }
            val networkState = networkStore.state.value
            val option = networkState.selectedOption
            if (!networkState.followsSystem() && option == null) {
                stopClient()
                checkActive(ticket)
                waitForNetwork()
                return
            }
            val networkHandle = if (networkState.followsSystem()) 0L else checkNotNull(option).handle
            store.transition(FrpPhase.STARTING, TextResource(R.string.frp_message_starting))
            layout.prepare()
            if (!networkState.followsSystem()) {
                try {
                    probeNetworkBinding(networkHandle)
                } catch (error: Exception) {
                    withContext(Dispatchers.Main) {
                        networkStore.reportBindingFailure(checkNotNull(option).interfaceName, networkHandle)
                    }
                    throw error
                }
            }
            val staleGroup = synchronized(processLock) {
                checkActive(ticket)
                OwnedProcesses.groupFor(layout.executable.absolutePath, layout.config.absolutePath)
            }
            OwnedProcesses.terminateGroup(staleGroup)
            delay(200)
            OwnedProcesses.terminateGroup(staleGroup, OsConstants.SIGKILL)
            val allowUnsafe = store.state.value.allowUnsafeTokenCommand
            check(layout.config.isFile && layout.verify(allowUnsafeTokenCommand = allowUnsafe)) { "FRP configuration validation failed" }
            val port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val password = UUID.randomUUID().toString()
            val child = synchronized(processLock) {
                checkActive(ticket)
                val arguments = listOf("-c", layout.config.absolutePath) + layout.unsafeArguments(allowUnsafe)
                layout.builder(*arguments.toTypedArray()).apply {
                    environment().putAll(
                        mapOf(
                            "XRAYDROID_FRP_NETWORK_HANDLE" to networkHandle.toString(),
                            "XRAYDROID_FRP_ADMIN_PORT" to port.toString(),
                            "XRAYDROID_FRP_ADMIN_USER" to "xraydroid",
                            "XRAYDROID_FRP_ADMIN_PASSWORD" to password
                        )
                    )
                }.start().also { process = it }
            }
            val authorization = "Basic " + Base64.getEncoder().encodeToString("xraydroid:$password".toByteArray(Charsets.UTF_8))
            adminPort = port
            adminAuthorization = authorization
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadline) {
                synchronized(processLock) {
                    checkActive(ticket)
                    processGroup = processGroup ?: OwnedProcesses.groupFor(layout.executable.absolutePath, layout.config.absolutePath)
                }
                check(child.isAlive) { "FRP client exited before readiness" }
                val statuses = pollStatus(port, authorization)
                if (statuses != null) {
                    synchronized(processLock) {
                        checkActive(ticket)
                        processGroup = OwnedProcesses.groupFor(layout.executable.absolutePath, layout.config.absolutePath)
                    }
                    check(processGroup != null) { "FRP process session is unavailable" }
                    store.transition(FrpPhase.RUNNING, null, networkState.networkLabel())
                    store.updateRuntime(statuses)
                    updateNotification()
                    monitor(child, ticket)
                    monitorStatus(child, ticket)
                    return
                }
                delay(200)
            }
            error("FRP control endpoint readiness timed out")
        }

        private fun probeNetworkBinding(networkHandle: Long) {
            val probe = layout.builder("--version").apply {
                environment()["XRAYDROID_FRP_NETWORK_HANDLE"] = networkHandle.toString()
            }.start()
            try {
                check(probe.waitFor(5, TimeUnit.SECONDS)) { "Native FRP network binding probe timed out" }
                check(probe.exitValue() == 0) { "Native FRP network binding probe failed" }
            } finally {
                if (probe.isAlive) probe.destroyForcibly()
            }
        }

        private fun monitorStatus(child: java.lang.Process, ticket: Long) {
            val port = adminPort
            val authorization = adminAuthorization
            scope.launch {
                while (generation.get() == ticket && process === child && child.isAlive) {
                    delay(2000)
                    val latest = pollStatus(port, authorization)
                    if (generation.get() == ticket && process === child && child.isAlive) {
                        val previous = store.state.value.connection.phase
                        store.updateRuntime(latest)
                        if (store.state.value.connection.phase != previous) {
                            updateNotification()
                        }
                    }
                }
            }
        }

        private fun pollStatus(port: Int, authorization: String): FrpRuntimeStatus? = runCatching {
            val connection = URL("http://127.0.0.1:$port/api/xraydroid/status").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 1000
                connection.readTimeout = 1000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Authorization", authorization)
                check(connection.responseCode == 200) { "FRP status endpoint unavailable" }
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val status = json.getJSONObject("connection")
                val phase = when (status.getString("state")) {
                    "connecting" -> FrpConnectionPhase.CONNECTING
                    "connected" -> FrpConnectionPhase.CONNECTED
                    "retrying" -> FrpConnectionPhase.RETRYING
                    "reconnecting" -> FrpConnectionPhase.RECONNECTING
                    else -> FrpConnectionPhase.UNKNOWN
                }
                val entries = json.getJSONArray("proxies")
                val proxies = buildList {
                    repeat(entries.length()) { index ->
                        val entry = entries.getJSONObject(index)
                        add(
                            FrpProxyStatus(
                                entry.getString("name"),
                                entry.getString("type"),
                                entry.getString("status"),
                                entry.optString("remote_addr")
                            )
                        )
                    }
                }.sortedWith(compareBy({ it.type }, { it.name }))
                // 僅保留固定錯誤代碼；未知分類不顯示原始值。
                val error = status.optString("error").takeIf { it in errorDetails }.orEmpty()
                FrpRuntimeStatus(FrpConnectionStatus(phase, error, status.getInt("attempts")), proxies)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

        private fun monitor(child: java.lang.Process, ticket: Long) {
            scope.launch {
                child.waitFor()
                mutex.withLock {
                    val unexpected = synchronized(processLock) {
                        if (process !== child || generation.get() != ticket) {
                            false
                        } else {
                            process = null
                            desiredRunning = false
                            true
                        }
                    }
                    if (!unexpected) return@withLock
                    persistDesired()
                    OwnedProcesses.terminateGroup(processGroup, OsConstants.SIGKILL)
                    processGroup = null
                    store.transition(FrpPhase.ERROR, TextResource(R.string.frp_message_stopped_unexpectedly))
                    finish(ticket)
                }
            }
        }

        private suspend fun stopClient() {
            val (group, child) = synchronized(processLock) {
                if (destroyed) throw CancellationException()
                val group = processGroup ?: OwnedProcesses.groupFor(layout.executable.absolutePath, layout.config.absolutePath)
                group to process.also { process = null }
            }
            store.transition(FrpPhase.STOPPING, TextResource(R.string.frp_message_stopping))
            OwnedProcesses.terminateGroup(group)
            child?.destroy()
            if (child != null && !child.waitFor(8, TimeUnit.SECONDS)) {
                child.destroyForcibly()
                child.waitFor(2, TimeUnit.SECONDS)
            }
            delay(200)
            OwnedProcesses.terminateGroup(group, OsConstants.SIGKILL)
            processGroup = null
            store.transition(FrpPhase.STOPPED, TextResource(R.string.frp_message_stopped))
        }

        private suspend fun finish(ticket: Long) = withContext(Dispatchers.Main) {
            synchronized(lifecycleLock) {
                if (generation.get() == ticket && !desiredRunning && process == null) {
                    if (runtimes.remove(id, this@Runtime)) dispose()
                }
                if (generation.get() == ticket && pendingCommands == 0 &&
                    runtimes.values.none { it.desiredRunning || it.process != null }
                ) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(activeStartId)
                } else {
                    updateNotification()
                }
            }
        }
    }

    private fun notification(): Notification {
        val activity = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).putExtra("open_frp", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val active = runtimes.values.filter { it.desiredRunning || it.process != null }.sortedBy { it.id }
        val lines = active.map { runtime ->
            val name = FrpStore.instances.value.firstOrNull { it.id == runtime.id }?.name.orEmpty()
            val state = FrpStore.forInstance(runtime.id).state.value
            val status = if (state.phase == FrpPhase.RUNNING) {
                state.connection.phase.titleRes
            } else {
                when (state.phase) {
                    FrpPhase.STOPPED -> R.string.frp_phase_stopped
                    FrpPhase.VALIDATING -> R.string.frp_phase_validating
                    FrpPhase.STARTING -> R.string.frp_phase_starting
                    FrpPhase.STOPPING -> R.string.frp_phase_stopping
                    FrpPhase.WAITING_FOR_NETWORK -> R.string.frp_phase_waiting_network
                    else -> R.string.frp_phase_error
                }
            }
            "$name · ${getString(status)}"
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_server)
            .setContentTitle(getString(R.string.frp_notification_title))
            .setContentText(lines.joinToString(" · ").ifEmpty { getString(R.string.frp_notification_preparing) })
            .setStyle(Notification.InboxStyle().also { style -> lines.forEach { style.addLine(it) } })
            .setContentIntent(activity)
            .setOngoing(true)
            .apply {
                active.take(3).forEach { runtime ->
                    val name = FrpStore.instances.value.firstOrNull { it.id == runtime.id }?.name.orEmpty()
                    val stop = PendingIntent.getService(
                        this@FrpService,
                        runtime.id.hashCode(),
                        Intent(this@FrpService, FrpService::class.java).setAction(ACTION_STOP)
                            .setData("xraydroid://frp/${runtime.id}".toUri())
                            .putExtra(EXTRA_INSTANCE_ID, runtime.id),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    addAction(Notification.Action.Builder(null, "${getString(R.string.frp_notification_stop)} $name", stop).build())
                }
            }.build()
    }

    private fun updateNotification() = synchronized(lifecycleLock) {
        if (!destroyed) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    override fun onDestroy() {
        synchronized(lifecycleLock) { destroyed = true }
        scope.cancel()
        runtimes.values.forEach { it.destroy() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "io.github.xraydroid.FRP_START"
        const val ACTION_STOP = "io.github.xraydroid.FRP_STOP"
        const val ACTION_RESTART = "io.github.xraydroid.FRP_RESTART"
        const val EXTRA_INSTANCE_ID = "frp_instance_id"
        private const val CHANNEL = "frp_client"
        private const val NOTIFICATION_ID = 7000

        fun dispatch(context: Context, action: String, instanceId: String = FrpStore.DEFAULT_INSTANCE_ID) {
            require(action in setOf(ACTION_START, ACTION_STOP, ACTION_RESTART)) { "Unknown FRP action" }
            ContextCompat.startForegroundService(
                context,
                Intent(context, FrpService::class.java).setAction(action).putExtra(EXTRA_INSTANCE_ID, instanceId)
            )
        }
    }
}
