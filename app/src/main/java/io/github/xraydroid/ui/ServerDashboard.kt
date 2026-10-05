package io.github.xraydroid.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.xraydroid.BuildConfig
import io.github.xraydroid.R
import io.github.xraydroid.runtime.ServerPhase
import io.github.xraydroid.runtime.ServerState

@Composable
fun ServerDashboard(
    state: ServerState,
    onOpenSettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onOpenPanel: () -> Unit
) {
    var logsExpanded by rememberSaveable { mutableStateOf(false) }
    val running = state.phase == ServerPhase.RUNNING
    val busy = state.phase == ServerPhase.STARTING || state.phase == ServerPhase.STOPPING
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Box(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val settingsLabel = stringResource(R.string.dashboard_settings)
                        Text("XrayDroid", style = MaterialTheme.typography.headlineLarge)
                        TextButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.semantics { contentDescription = settingsLabel }
                        ) { Text(settingsLabel) }
                    }
                }
                item {
                    StatusCard(
                        state = state,
                        onStart = onStart,
                        onStop = onStop,
                        onRestart = onRestart
                    )
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(
                            Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(stringResource(R.string.dashboard_panel_title), style = MaterialTheme.typography.titleLarge)
                            Text(
                                stringResource(R.string.dashboard_panel_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(R.string.dashboard_panel_credentials),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            SelectionContainer {
                                Text(
                                    state.panelUrl,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                            }
                            Button(
                                onClick = onOpenPanel,
                                enabled = running && !busy,
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                shape = RoundedCornerShape(20.dp)
                            ) { Text(stringResource(R.string.dashboard_panel_open)) }
                            if (!running) {
                                Text(stringResource(R.string.dashboard_panel_requires_service), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Text(stringResource(R.string.dashboard_versions_title), style = MaterialTheme.typography.titleMedium)
                            VersionRow(stringResource(R.string.dashboard_core_3xui), BuildConfig.XUI_VERSION)
                            VersionRow(stringResource(R.string.dashboard_core_xray), BuildConfig.XRAY_VERSION)
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.dashboard_logs_title), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { logsExpanded = !logsExpanded }) {
                            Text(
                                stringResource(
                                    if (logsExpanded) R.string.dashboard_logs_collapse else R.string.dashboard_logs_expand
                                )
                            )
                        }
                    }
                    if (!logsExpanded) {
                        Text(
                            stringResource(R.string.dashboard_logs_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (state.logs.isEmpty()) {
                        Text(
                            stringResource(R.string.dashboard_logs_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (logsExpanded) {
                    itemsIndexed(state.logs.takeLast(80)) { _, line ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            SelectionContainer {
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                    if (state.logs.isNotEmpty()) {
                        item {
                            val shown = minOf(state.logs.size, 80)
                            Text(
                                pluralStringResource(R.plurals.dashboard_logs_shown_count, shown, shown),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun StatusCard(state: ServerState, onStart: () -> Unit, onStop: () -> Unit, onRestart: () -> Unit) {
    val running = state.phase == ServerPhase.RUNNING
    val busy = state.phase == ServerPhase.STARTING || state.phase == ServerPhase.STOPPING
    val failed = state.phase == ServerPhase.ERROR
    val waiting = state.phase == ServerPhase.WAITING_FOR_NETWORK
    val container by animateColorAsState(
        targetValue = when {
            failed -> MaterialTheme.colorScheme.errorContainer
            running -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        label = "serviceStatusColor"
    )
    val contentColor = when {
        failed -> MaterialTheme.colorScheme.onErrorContainer
        running -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Card(
        shape = RoundedCornerShape(36.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = contentColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.size(10.dp).background(contentColor, CircleShape))
                Text(stringResource(R.string.dashboard_service_label), style = MaterialTheme.typography.labelLarge)
            }
            Text(
                text = stringResource(
                    when (state.phase) {
                        ServerPhase.WAITING_FOR_NETWORK -> R.string.dashboard_status_waiting_network_title
                        ServerPhase.STOPPED -> R.string.dashboard_status_stopped_title
                        ServerPhase.STARTING -> R.string.dashboard_status_starting_title
                        ServerPhase.RUNNING -> R.string.dashboard_status_running_title
                        ServerPhase.STOPPING -> R.string.dashboard_status_stopping_title
                        ServerPhase.ERROR -> R.string.dashboard_status_error_title
                    }
                ),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Text(
                text = stringResource(
                    when (state.phase) {
                        ServerPhase.WAITING_FOR_NETWORK -> R.string.dashboard_status_waiting_network_detail
                        ServerPhase.STOPPED -> R.string.dashboard_status_stopped_detail
                        ServerPhase.STARTING -> R.string.dashboard_status_starting_detail
                        ServerPhase.RUNNING -> R.string.dashboard_status_running_detail
                        ServerPhase.STOPPING -> R.string.dashboard_status_stopping_detail
                        ServerPhase.ERROR -> R.string.dashboard_status_error_detail
                    }
                ),
                style = MaterialTheme.typography.bodyLarge
            )
            state.outboundNetworkLabel?.let { label ->
                Text(stringResource(R.string.dashboard_outbound_network, label.resolve()), style = MaterialTheme.typography.labelLarge)
            }
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = contentColor)
            }
            val failure = state.message
            if (failed && failure != null) {
                SelectionContainer {
                    Text(
                        failure.resolve(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
            if (running) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onRestart,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
                        border = BorderStroke(1.dp, contentColor),
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(20.dp)
                    ) { Text(stringResource(R.string.dashboard_restart)) }
                    Button(
                        onClick = onStop,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(20.dp)
                    ) { Text(stringResource(R.string.dashboard_stop)) }
                }
            } else if (waiting) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(20.dp)
                ) { Text(stringResource(R.string.dashboard_stop)) }
            } else {
                Button(
                    onClick = onStart,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text(
                        stringResource(if (failed) R.string.dashboard_start_again else R.string.dashboard_start),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionRow(name: String, version: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(
            version,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        )
    }
}
