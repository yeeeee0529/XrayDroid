package io.github.xraydroid

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.net.toUri
import io.github.xraydroid.runtime.FrpService
import io.github.xraydroid.runtime.FrpStore
import io.github.xraydroid.runtime.NetworkStore
import io.github.xraydroid.runtime.ServerStore
import io.github.xraydroid.runtime.XuiService
import io.github.xraydroid.ui.FrpScreen
import io.github.xraydroid.ui.OutboundNetworkScreen
import io.github.xraydroid.ui.ServerDashboard
import io.github.xraydroid.ui.SettingsScreen
import io.github.xraydroid.ui.theme.XrayDroidTheme
import kotlin.coroutines.cancellation.CancellationException

class MainActivity : ComponentActivity() {
    private var pendingAction: String? = null
    private var openFrpRequest by mutableStateOf(0)
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "未允許通知；服務狀態仍可在 App 中查看。", Toast.LENGTH_LONG).show()
        }
        pendingAction?.let { dispatchService(it) }
        pendingAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingAction = savedInstanceState?.getString(PENDING_ACTION)
        if (intent.getBooleanExtra("open_frp", false)) openFrpRequest++
        NetworkStore.initialize(applicationContext)
        FrpStore.initialize(applicationContext)
        enableEdgeToEdge()
        setContent {
            XrayDroidTheme {
                val state by ServerStore.state.collectAsState()
                val networkState by NetworkStore.state.collectAsState()
                val frpState by FrpStore.state.collectAsState()
                var page by rememberSaveable { mutableStateOf(if (intent.getBooleanExtra("open_frp", false)) "frp" else "home") }
                LaunchedEffect(openFrpRequest) {
                    if (openFrpRequest > 0) page = "frp"
                }
                // 返回層級：home → settings → network / frp；首頁的返回交給系統動畫。
                fun parentOf(target: String): String? = when (target) {
                    "settings" -> "home"
                    "network", "frp" -> "settings"
                    else -> null
                }
                // in-app predictive back 疊層：backTarget 為手勢預覽中的目標頁。
                var backTarget by remember { mutableStateOf<String?>(null) }
                val backProgress = remember { Animatable(0f) }
                val content: @Composable (String) -> Unit = { current ->
                    when (current) {
                        "settings" -> SettingsScreen(
                            onBack = { page = "home" },
                            onOpenNetwork = { page = "network" },
                            onOpenFrp = { page = "frp" }
                        )
                        "network" -> OutboundNetworkScreen(
                            serverState = state,
                            networkState = networkState,
                            onBack = { page = "settings" },
                            onSelectNetwork = NetworkStore::select,
                            onSelectInterface = NetworkStore::selectInterface,
                            onRefreshInterfaces = NetworkStore::refreshInterfaces
                        )
                        "frp" -> FrpScreen(
                            state = frpState,
                            onBack = { page = "settings" },
                            onStart = { dispatchWithNotificationPermission(FrpService.ACTION_START) },
                            onStop = { FrpService.dispatch(this, FrpService.ACTION_STOP) },
                            onRestart = { dispatchWithNotificationPermission(FrpService.ACTION_RESTART) }
                        )
                        else -> {
                            ServerDashboard(
                                state = state,
                                onOpenSettings = { page = "settings" },
                                onStart = { dispatchWithNotificationPermission(XuiService.ACTION_START) },
                                onStop = { XuiService.dispatch(this, XuiService.ACTION_STOP) },
                                onRestart = { dispatchWithNotificationPermission(XuiService.ACTION_RESTART) },
                                onOpenPanel = { openPanel(state.panelUrl) }
                            )
                        }
                    }
                }
                // 返回手勢期間預覽目標頁；commit 時切換、cancel 時回復前景頁。按鍵返回以 0 個事件完成，同樣進入 commit 分支。
                PredictiveBackHandler(enabled = parentOf(page) != null) { events ->
                    try {
                        events.collect { event: BackEventCompat ->
                            backTarget = parentOf(page)
                            backProgress.snapTo(event.progress.coerceIn(0f, 1f))
                        }
                        val target = parentOf(page)
                        backTarget = null
                        backProgress.snapTo(0f)
                        if (target != null) page = target
                    } catch (e: CancellationException) {
                        // 手勢取消：前景頁動畫回復原狀後移除疊層。
                        try {
                            backProgress.animateTo(0f, tween(150))
                        } finally {
                            backTarget = null
                        }
                    }
                }
                DisposableEffect(page) {
                    if (page == "frp") window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    onDispose {
                        if (page == "frp") window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    val target = backTarget
                    if (target == null) {
                        content(page)
                    } else {
                        // 背景：目標頁隨手勢放大；前景：當前頁縮小、加上圓角與陰影。
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val progress = backProgress.value
                                    scaleX = lerp(0.95f, 1f, progress)
                                    scaleY = scaleX
                                }
                        ) { content(target) }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val progress = backProgress.value
                                    scaleX = lerp(1f, 0.9f, progress)
                                    scaleY = scaleX
                                    shape = RoundedCornerShape((24f * progress).dp)
                                    clip = true
                                    shadowElevation = 48f * progress
                                }
                        ) { content(page) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_frp", false)) openFrpRequest++
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingAction?.let { outState.putString(PENDING_ACTION, it) }
        super.onSaveInstanceState(outState)
    }

    private fun dispatchWithNotificationPermission(action: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAction = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            dispatchService(action)
        }
    }

    private fun dispatchService(action: String) {
        if (action == FrpService.ACTION_START || action == FrpService.ACTION_RESTART) {
            FrpService.dispatch(this, action)
        } else {
            XuiService.dispatch(this, action)
        }
    }

    private fun openPanel(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "找不到瀏覽器，請安裝瀏覽器後再開啟管理面板。", Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val PENDING_ACTION = "pending_service_action"
    }
}
