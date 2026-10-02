package io.github.xraydroid

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import io.github.xraydroid.runtime.FrpService
import io.github.xraydroid.runtime.FrpStore
import io.github.xraydroid.runtime.NetworkStore
import io.github.xraydroid.runtime.ServerStore
import io.github.xraydroid.runtime.XuiService
import io.github.xraydroid.ui.FrpScreen
import io.github.xraydroid.ui.OutboundNetworkScreen
import io.github.xraydroid.ui.PredictiveBackNavigation
import io.github.xraydroid.ui.ServerDashboard
import io.github.xraydroid.ui.SettingsScreen
import io.github.xraydroid.ui.theme.XrayDroidTheme

class MainActivity : ComponentActivity() {
    private var pendingAction: String? = null
    private var openFrpRequest by mutableStateOf(0)
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_LONG).show()
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
                DisposableEffect(page) {
                    val secure = page == "frp"
                    if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    onDispose {
                        if (secure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
                PredictiveBackNavigation(
                    page = page,
                    parent = ::parentOf,
                    onNavigate = { page = it }
                ) { current, navigateBack ->
                    when (current) {
                        "settings" -> SettingsScreen(
                            onBack = navigateBack,
                            onOpenNetwork = { page = "network" },
                            onOpenFrp = { page = "frp" }
                        )
                        "network" -> OutboundNetworkScreen(
                            serverState = state,
                            networkState = networkState,
                            onBack = navigateBack,
                            onSelectNetwork = NetworkStore::select,
                            onSelectInterface = NetworkStore::selectInterface,
                            onRefreshInterfaces = NetworkStore::refreshInterfaces
                        )
                        "frp" -> FrpScreen(
                            state = frpState,
                            onBack = navigateBack,
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
            Toast.makeText(this, R.string.browser_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val PENDING_ACTION = "pending_service_action"
    }
}
