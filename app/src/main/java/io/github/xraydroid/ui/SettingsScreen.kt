package io.github.xraydroid.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.xraydroid.R
import io.github.xraydroid.runtime.NetworkState
import io.github.xraydroid.runtime.OutboundNetworkMode
import io.github.xraydroid.runtime.ServerPhase
import io.github.xraydroid.runtime.ServerState

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenNetwork: () -> Unit, onOpenFrp: () -> Unit) {
    SettingsPage(R.string.settings_title, R.string.nav_back_home, onBack) {
        item {
            SettingsEntry(R.string.settings_network_title, R.string.settings_network_description, onOpenNetwork)
        }
        item {
            SettingsEntry(R.string.settings_frp_title, R.string.settings_frp_description, onOpenFrp)
        }
    }
}

@Composable
private fun SettingsEntry(@StringRes title: Int, @StringRes description: Int, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(description), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun OutboundNetworkScreen(
    serverState: ServerState,
    networkState: NetworkState,
    onBack: () -> Unit,
    onSelectNetwork: (OutboundNetworkMode) -> Unit,
    onSelectInterface: (String) -> Unit,
    onRefreshInterfaces: () -> Unit
) {
    val busy = serverState.phase == ServerPhase.STARTING || serverState.phase == ServerPhase.STOPPING
    SettingsPage(R.string.network_title, R.string.nav_back_settings, onBack) {
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

@Composable
internal fun SettingsPage(
    @StringRes title: Int,
    @StringRes backLabel: Int,
    onBack: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
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
                        val back = stringResource(backLabel)
                        Text(stringResource(title), style = MaterialTheme.typography.headlineLarge)
                        TextButton(
                            onClick = onBack,
                            modifier = Modifier.semantics { contentDescription = back }
                        ) { Text(back) }
                    }
                }
                content()
            }
        }
    }
}
