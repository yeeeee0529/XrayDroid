package io.github.xraydroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.xraydroid.runtime.NetworkState
import io.github.xraydroid.runtime.OutboundNetworkMode
import io.github.xraydroid.runtime.ServerPhase
import io.github.xraydroid.runtime.ServerState

@Composable
fun SettingsScreen(
    serverState: ServerState,
    networkState: NetworkState,
    onBack: () -> Unit,
    onSelectNetwork: (OutboundNetworkMode) -> Unit,
    onSelectInterface: (String) -> Unit,
    onRefreshInterfaces: () -> Unit
) {
    val busy = serverState.phase == ServerPhase.STARTING || serverState.phase == ServerPhase.STOPPING
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Box(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(24.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("設定", style = MaterialTheme.typography.headlineLarge)
                        TextButton(
                            onClick = onBack,
                            modifier = Modifier.semantics { contentDescription = "返回首頁" }
                        ) { Text("返回首頁") }
                    }
                }
                item {
                    NetworkCard(
                        state = networkState,
                        appliedNetworkLabel = serverState.outboundNetworkLabel,
                        enabled = !busy,
                        onSelect = onSelectNetwork,
                        onSelectInterface = onSelectInterface,
                        onRefresh = onRefreshInterfaces
                    )
                }
            }
        }
    }
}
