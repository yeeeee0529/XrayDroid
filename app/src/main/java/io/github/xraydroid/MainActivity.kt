package io.github.xraydroid

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import io.github.xraydroid.runtime.NetworkStore
import io.github.xraydroid.runtime.ServerStore
import io.github.xraydroid.runtime.XuiService
import io.github.xraydroid.ui.ServerDashboard
import io.github.xraydroid.ui.SettingsScreen
import io.github.xraydroid.ui.theme.XrayDroidTheme

class MainActivity : ComponentActivity() {
    private var pendingAction: String? = null
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "未允許通知；服務狀態仍可在 App 中查看。", Toast.LENGTH_LONG).show()
        }
        pendingAction?.let { XuiService.dispatch(this, it) }
        pendingAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingAction = savedInstanceState?.getString(PENDING_ACTION)
        NetworkStore.initialize(applicationContext)
        enableEdgeToEdge()
        setContent {
            XrayDroidTheme {
                val state by ServerStore.state.collectAsState()
                val networkState by NetworkStore.state.collectAsState()
                var settingsVisible by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = settingsVisible) { settingsVisible = false }
                if (settingsVisible) {
                    SettingsScreen(
                        serverState = state,
                        networkState = networkState,
                        onBack = { settingsVisible = false },
                        onSelectNetwork = NetworkStore::select,
                        onSelectInterface = NetworkStore::selectInterface,
                        onRefreshInterfaces = NetworkStore::refreshInterfaces
                    )
                } else {
                    ServerDashboard(
                        state = state,
                        onOpenSettings = { settingsVisible = true },
                        onStart = { dispatchWithNotificationPermission(XuiService.ACTION_START) },
                        onStop = { XuiService.dispatch(this, XuiService.ACTION_STOP) },
                        onRestart = { dispatchWithNotificationPermission(XuiService.ACTION_RESTART) },
                        onOpenPanel = { openPanel(state.panelUrl) }
                    )
                }
            }
        }
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
