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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
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

class XuiService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val processLock = Any()
    private var activeTicket = 0L
    private var activeStartId = 0
    private lateinit var layout: RuntimeLayout

    @Volatile private var process: java.lang.Process? = null
    private val paths: Set<String> get() = setOf(layout.xui.absolutePath, layout.xray.absolutePath)

    override fun onCreate() {
        super.onCreate()
        layout = RuntimeLayout(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "本機伺服器", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        val ticket = generation.incrementAndGet()
        val notification = notification("正在準備本機服務")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        scope.launch {
            mutex.withLock {
                if (generation.get() != ticket) return@withLock
                activeTicket = ticket
                activeStartId = startId
                try {
                    when (action) {
                        ACTION_STOP -> stopServer()
                        ACTION_RESTART -> {
                            stopServer()
                            startServer(ticket)
                        }
                        ACTION_START -> startServer(ticket)
                    }
                } catch (_: CancellationException) {
                    stopServer()
                } catch (error: Exception) {
                    stopServer()
                    ServerStore.log("Server startup failed: ${error.javaClass.simpleName}")
                    ServerStore.transition(ServerPhase.ERROR, "服務啟動失敗，請確認核心檔案與連接埠後重試")
                }
                if (generation.get() == ticket && process == null) {
                    finishService(ticket, startId)
                }
            }
        }
        return START_STICKY
    }

    private suspend fun startServer(ticket: Long) {
        if (process?.isAlive == true) {
            updateNotification("服務執行中 · 127.0.0.1:2053")
            return
        }
        ServerStore.transition(ServerPhase.STARTING, "正在啟動 3x-ui 與 Xray")
        layout.prepare()
        OwnedProcesses.terminate(paths)
        delay(300)
        OwnedProcesses.terminate(paths, OsConstants.SIGKILL)
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 2053))
        }
        val child = synchronized(processLock) {
            if (generation.get() != ticket) throw CancellationException()
            layout.builder().start().also { process = it }
        }
        // 核心輸出可能包含登入資料；只顯示 App 自身的生命週期事件。
        scope.launch {
            runCatching {
                child.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (input.read(buffer) != -1) Unit
                }
            }
        }
        ServerStore.log("3x-ui process started")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (System.nanoTime() < deadline) {
            if (generation.get() != ticket) throw CancellationException()
            check(child.isAlive) { "3x-ui exited before panel readiness" }
            if (panelReady()) {
                ServerStore.transition(ServerPhase.RUNNING, "管理面板已就緒，服務持續於背景執行")
                ServerStore.log("Panel is ready at 127.0.0.1:2053")
                updateNotification("服務執行中 · 127.0.0.1:2053")
                monitor(child)
                return
            }
            delay(250)
        }
        error("Panel readiness timed out")
    }

    private fun panelReady(): Boolean = runCatching {
        val connection = URL(
            ServerStore.state.value.panelUrl
        ).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 500
            connection.readTimeout = 500
            connection.instanceFollowRedirects = false
            connection.responseCode == 200 && connection.contentType.orEmpty().contains("text/html")
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private fun monitor(child: java.lang.Process) {
        scope.launch {
            child.waitFor()
            mutex.withLock {
                if (process !== child) return@withLock
                process = null
                OwnedProcesses.terminate(paths, OsConstants.SIGKILL)
                ServerStore.transition(ServerPhase.ERROR, "服務意外停止，請重新啟動")
                ServerStore.log("3x-ui process exited unexpectedly")
                finishService(activeTicket, activeStartId)
            }
        }
    }

    private suspend fun finishService(ticket: Long, startId: Int) {
        withContext(Dispatchers.Main) {
            if (generation.get() == ticket) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
        }
    }

    private suspend fun stopServer() {
        ServerStore.transition(ServerPhase.STOPPING, "正在停止服務")
        val child = process
        process = null
        child?.destroy()
        if (child != null && !child.waitFor(8, TimeUnit.SECONDS)) {
            child.destroyForcibly()
            child.waitFor(2, TimeUnit.SECONDS)
        }
        OwnedProcesses.terminate(paths)
        delay(200)
        OwnedProcesses.terminate(paths, OsConstants.SIGKILL)
        ServerStore.transition(ServerPhase.STOPPED, "服務已停止")
        ServerStore.log("Server processes stopped")
    }

    private fun notification(text: String): Notification {
        val activity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, XuiService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_server)
            .setContentTitle("XrayDroid 本機伺服器")
            .setContentText(text)
            .setContentIntent(activity)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止服務", stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(
            NotificationManager::class.java
        ).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        generation.incrementAndGet()
        scope.cancel()
        synchronized(processLock) {
            process?.destroy()
            process = null
            OwnedProcesses.terminate(paths, OsConstants.SIGKILL)
        }
        if (ServerStore.state.value.phase != ServerPhase.ERROR) {
            ServerStore.transition(ServerPhase.STOPPED, "服務已停止")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "io.github.xraydroid.START"
        const val ACTION_STOP = "io.github.xraydroid.STOP"
        const val ACTION_RESTART = "io.github.xraydroid.RESTART"
        private const val CHANNEL = "xui_server"
        private const val NOTIFICATION_ID = 2053

        fun dispatch(context: Context, action: String) {
            require(
                action in setOf(ACTION_START, ACTION_STOP, ACTION_RESTART)
            ) { "Unknown server action" }
            ContextCompat.startForegroundService(
                context,
                Intent(context, XuiService::class.java).setAction(action)
            )
        }
    }
}
