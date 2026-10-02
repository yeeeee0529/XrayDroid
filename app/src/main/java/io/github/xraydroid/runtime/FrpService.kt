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
import io.github.xraydroid.MainActivity
import io.github.xraydroid.R
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class FrpService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val processLock = Any()
    private val generation = AtomicLong()
    private lateinit var layout: FrpLayout

    @Volatile private var process: java.lang.Process? = null

    @Volatile private var desiredRunning = false
    private var adminPort = 0
    private var adminAuthorization = ""

    @Volatile private var processGroup: Int? = null
    private val paths: Set<String> get() = setOf(layout.executable.absolutePath)

    override fun onCreate() {
        super.onCreate()
        layout = FrpLayout(this)
        FrpStore.initialize(applicationContext)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.frp_notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系統重建僅恢復這個獨立服務，不影響 3x-ui／Xray。
        val action = intent?.action ?: ACTION_START
        val ticket = synchronized(processLock) {
            desiredRunning = action != ACTION_STOP
            generation.incrementAndGet()
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification(getString(R.string.frp_notification_preparing)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification(getString(R.string.frp_notification_preparing)))
        }
        scope.launch {
            mutex.withLock {
                if (generation.get() != ticket) return@withLock
                try {
                    if (!desiredRunning) {
                        stopClient()
                        finish(ticket, startId)
                    } else {
                        if (action == ACTION_RESTART) stopClient()
                        startClient(ticket, startId)
                    }
                } catch (_: CancellationException) {
                    stopClient()
                } catch (_: Exception) {
                    stopClient()
                    if (generation.get() == ticket) {
                        desiredRunning = false
                        FrpStore.transition(FrpPhase.ERROR, TextResource(R.string.frp_message_start_failed))
                        finish(ticket, startId)
                    }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun startClient(ticket: Long, startId: Int) {
        val existing = process
        if (existing?.isAlive == true) {
            monitor(existing, ticket, startId)
            monitorStatus(existing, ticket)
            updateNotification(getString(FrpStore.state.value.connection.phase.titleRes))
            return
        }
        FrpStore.transition(FrpPhase.STARTING, TextResource(R.string.frp_message_starting))
        layout.prepare()
        val staleGroup = OwnedProcesses.groupFor(layout.executable.absolutePath)
        OwnedProcesses.terminateGroup(staleGroup)
        OwnedProcesses.terminate(paths)
        delay(200)
        OwnedProcesses.terminateGroup(staleGroup, OsConstants.SIGKILL)
        OwnedProcesses.terminate(paths, OsConstants.SIGKILL)
        val allowUnsafe = FrpStore.state.value.allowUnsafeTokenCommand
        check(layout.config.isFile && layout.verify(allowUnsafeTokenCommand = allowUnsafe)) { "FRP configuration validation failed" }
        val port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val password = UUID.randomUUID().toString()
        val child = synchronized(processLock) {
            if (generation.get() != ticket || !desiredRunning) throw CancellationException()
            val arguments = listOf("-c", layout.config.absolutePath) + layout.unsafeArguments(allowUnsafe)
            layout.builder(*arguments.toTypedArray()).apply {
                environment().putAll(
                    mapOf(
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
            processGroup = processGroup ?: OwnedProcesses.groupFor(layout.executable.absolutePath)
            if (generation.get() != ticket || !desiredRunning) throw CancellationException()
            check(child.isAlive) { "FRP client exited before readiness" }
            val statuses = pollStatus(port, authorization)
            if (statuses != null) {
                processGroup = OwnedProcesses.groupFor(layout.executable.absolutePath)
                check(processGroup != null) { "FRP process session is unavailable" }
                FrpStore.transition(FrpPhase.RUNNING, null)
                FrpStore.updateRuntime(statuses)
                updateNotification(getString(FrpStore.state.value.connection.phase.titleRes))
                monitor(child, ticket, startId)
                monitorStatus(child, ticket)
                return
            }
            delay(200)
        }
        error("FRP control endpoint readiness timed out")
    }

    private fun monitorStatus(child: java.lang.Process, ticket: Long) {
        val port = adminPort
        val authorization = adminAuthorization
        scope.launch {
            while (generation.get() == ticket && process === child && child.isAlive) {
                delay(2000)
                val latest = pollStatus(port, authorization)
                if (generation.get() == ticket && process === child && child.isAlive) {
                    val previous = FrpStore.state.value.connection.phase
                    FrpStore.updateRuntime(latest)
                    if (FrpStore.state.value.connection.phase != previous) {
                        updateNotification(getString(FrpStore.state.value.connection.phase.titleRes))
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

    private fun monitor(child: java.lang.Process, ticket: Long, startId: Int) {
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
                OwnedProcesses.terminateGroup(processGroup, OsConstants.SIGKILL)
                processGroup = null
                OwnedProcesses.terminate(paths, OsConstants.SIGKILL)
                FrpStore.transition(FrpPhase.ERROR, TextResource(R.string.frp_message_stopped_unexpectedly))
                finish(ticket, startId)
            }
        }
    }

    private suspend fun stopClient() {
        FrpStore.transition(FrpPhase.STOPPING, TextResource(R.string.frp_message_stopping))
        val group = processGroup ?: OwnedProcesses.groupFor(layout.executable.absolutePath)
        val child = synchronized(processLock) { process.also { process = null } }
        OwnedProcesses.terminateGroup(group)
        child?.destroy()
        if (child != null && !child.waitFor(8, TimeUnit.SECONDS)) {
            child.destroyForcibly()
            child.waitFor(2, TimeUnit.SECONDS)
        }
        OwnedProcesses.terminate(paths)
        delay(200)
        OwnedProcesses.terminate(paths, OsConstants.SIGKILL)
        OwnedProcesses.terminateGroup(group, OsConstants.SIGKILL)
        processGroup = null
        FrpStore.transition(FrpPhase.STOPPED, TextResource(R.string.frp_message_stopped))
    }

    private suspend fun finish(ticket: Long, startId: Int) = withContext(Dispatchers.Main) {
        if (generation.get() == ticket) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
    }

    private fun notification(message: String): Notification {
        val activity = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).putExtra("open_frp", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            11,
            Intent(this, FrpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_server)
            .setContentTitle(getString(R.string.frp_notification_title))
            .setContentText(message)
            .setContentIntent(activity)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.frp_notification_stop), stop).build())
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    override fun onDestroy() {
        synchronized(processLock) {
            OwnedProcesses.terminateGroup(processGroup ?: OwnedProcesses.groupFor(layout.executable.absolutePath), OsConstants.SIGKILL)
            processGroup = null
            desiredRunning = false
            generation.incrementAndGet()
        }
        scope.cancel()
        synchronized(processLock) {
            process?.destroyForcibly()
            process = null
            OwnedProcesses.terminate(paths, OsConstants.SIGKILL)
        }
        if (FrpStore.state.value.phase != FrpPhase.ERROR) FrpStore.transition(FrpPhase.STOPPED, TextResource(R.string.frp_message_stopped))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "io.github.xraydroid.FRP_START"
        const val ACTION_STOP = "io.github.xraydroid.FRP_STOP"
        const val ACTION_RESTART = "io.github.xraydroid.FRP_RESTART"
        private const val CHANNEL = "frp_client"
        private const val NOTIFICATION_ID = 7000

        fun dispatch(context: Context, action: String) {
            require(action in setOf(ACTION_START, ACTION_STOP, ACTION_RESTART)) { "Unknown FRP action" }
            ContextCompat.startForegroundService(context, Intent(context, FrpService::class.java).setAction(action))
        }
    }
}
