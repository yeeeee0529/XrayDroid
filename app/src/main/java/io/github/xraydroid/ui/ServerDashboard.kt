package io.github.xraydroid.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.xraydroid.BuildConfig
import io.github.xraydroid.R
import io.github.xraydroid.runtime.ServerPhase
import io.github.xraydroid.runtime.ServerState
import io.github.xraydroid.runtime.TrafficStatus
import io.github.xraydroid.ui.components.AppPage
import io.github.xraydroid.ui.components.ButtonLabel
import io.github.xraydroid.ui.components.IconBadge
import io.github.xraydroid.ui.components.SectionHeader
import io.github.xraydroid.ui.components.SegmentedColumn
import io.github.xraydroid.ui.components.SegmentedItem
import io.github.xraydroid.ui.components.StatusHero
import io.github.xraydroid.ui.components.StatusTone

private const val VISIBLE_LOG_LINES = 80

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
    AppPage(
        title = stringResource(R.string.app_name),
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.dashboard_settings))
            }
        }
    ) {
        item { StatusCard(state = state, onStart = onStart, onStop = onStop, onRestart = onRestart) }
        item { TrafficCard(state) }
        item { PanelCard(url = state.panelUrl, enabled = running && !busy, running = running, onOpenPanel = onOpenPanel) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(stringResource(R.string.dashboard_versions_title))
                SegmentedColumn {
                    SegmentedItem(0, 2, stringResource(R.string.dashboard_core_3xui), trailing = { VersionLabel(BuildConfig.XUI_VERSION) })
                    SegmentedItem(1, 2, stringResource(R.string.dashboard_core_xray), trailing = { VersionLabel(BuildConfig.XRAY_VERSION) })
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader(stringResource(R.string.dashboard_logs_title), Modifier.weight(1f))
                    TextButton(onClick = { logsExpanded = !logsExpanded }) {
                        val rotation by animateFloatAsState(if (logsExpanded) 180f else 0f, label = "logsChevron")
                        Text(stringResource(if (logsExpanded) R.string.dashboard_logs_collapse else R.string.dashboard_logs_expand))
                        Icon(
                            painterResource(R.drawable.ic_expand_more),
                            contentDescription = null,
                            modifier = Modifier.padding(start = 4.dp).size(18.dp).graphicsLayer { rotationZ = rotation }
                        )
                    }
                }
                if (!logsExpanded || state.logs.isEmpty()) {
                    Text(
                        stringResource(if (logsExpanded) R.string.dashboard_logs_empty else R.string.dashboard_logs_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
        if (logsExpanded && state.logs.isNotEmpty()) {
            item {
                val lines = state.logs.takeLast(VISIBLE_LOG_LINES)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // 整段日誌放在同一個選取範圍，可跨行複製。
                        SelectionContainer {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                lines.forEach { line ->
                                    Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                                }
                            }
                        }
                    }
                    Text(
                        pluralStringResource(R.plurals.dashboard_logs_shown_count, lines.size, lines.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TrafficCard(state: ServerState) {
    val running = state.phase == ServerPhase.RUNNING
    val ready = running && state.traffic.status == TrafficStatus.READY
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(stringResource(R.string.dashboard_traffic_title))
        SegmentedColumn {
            SegmentedItem(
                0,
                2,
                stringResource(R.string.dashboard_traffic_upload),
                leading = { IconBadge(R.drawable.ic_arrow_upward) },
                trailing = { TrafficSpeed(state.traffic.uploadBytesPerSecond, ready) }
            )
            SegmentedItem(
                1,
                2,
                stringResource(R.string.dashboard_traffic_download),
                leading = { IconBadge(R.drawable.ic_arrow_downward) },
                trailing = { TrafficSpeed(state.traffic.downloadBytesPerSecond, ready) }
            )
        }
        Text(
            stringResource(
                when {
                    !running -> R.string.dashboard_traffic_stopped
                    state.traffic.status == TrafficStatus.WAITING -> R.string.dashboard_traffic_waiting
                    state.traffic.status == TrafficStatus.UNAVAILABLE -> R.string.dashboard_traffic_unavailable
                    else -> R.string.dashboard_traffic_description
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

@Composable
private fun TrafficSpeed(bytesPerSecond: Double, ready: Boolean) {
    val value = if (!ready) {
        stringResource(R.string.dashboard_traffic_no_value)
    } else {
        when {
            bytesPerSecond >= 1_000_000_000 -> stringResource(R.string.dashboard_traffic_gbps, bytesPerSecond / 1_000_000_000)
            bytesPerSecond >= 1_000_000 -> stringResource(R.string.dashboard_traffic_mbps, bytesPerSecond / 1_000_000)
            bytesPerSecond >= 1000 -> stringResource(R.string.dashboard_traffic_kbps, bytesPerSecond / 1000)
            else -> stringResource(R.string.dashboard_traffic_bps, bytesPerSecond)
        }
    }
    Text(
        value,
        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
        color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusCard(state: ServerState, onStart: () -> Unit, onStop: () -> Unit, onRestart: () -> Unit) {
    val running = state.phase == ServerPhase.RUNNING
    val busy = state.phase == ServerPhase.STARTING || state.phase == ServerPhase.STOPPING
    val failed = state.phase == ServerPhase.ERROR
    val waiting = state.phase == ServerPhase.WAITING_FOR_NETWORK
    StatusHero(
        tone = when {
            failed -> StatusTone.Error
            running -> StatusTone.Active
            else -> StatusTone.Idle
        },
        busy = busy,
        icon = when {
            failed -> R.drawable.ic_error
            waiting -> R.drawable.ic_hourglass
            running -> R.drawable.ic_check
            else -> R.drawable.ic_power
        },
        label = stringResource(R.string.dashboard_service_label),
        title = stringResource(
            when (state.phase) {
                ServerPhase.WAITING_FOR_NETWORK -> R.string.dashboard_status_waiting_network_title
                ServerPhase.STOPPED -> R.string.dashboard_status_stopped_title
                ServerPhase.STARTING -> R.string.dashboard_status_starting_title
                ServerPhase.RUNNING -> R.string.dashboard_status_running_title
                ServerPhase.STOPPING -> R.string.dashboard_status_stopping_title
                ServerPhase.ERROR -> R.string.dashboard_status_error_title
            }
        ),
        titleModifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    ) {
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
        val failure = state.message
        if (failed && failure != null) {
            SelectionContainer {
                Text(failure.resolve(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
        if (running) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val contentColor = LocalContentColor.current
                OutlinedButton(
                    onClick = onRestart,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
                    border = BorderStroke(1.dp, contentColor),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                ) { ButtonLabel(R.drawable.ic_refresh, stringResource(R.string.dashboard_restart)) }
                Button(
                    onClick = onStop,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                ) { ButtonLabel(R.drawable.ic_stop, stringResource(R.string.dashboard_stop)) }
            }
        } else if (waiting) {
            Button(
                onClick = onStop,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            ) { ButtonLabel(R.drawable.ic_stop, stringResource(R.string.dashboard_stop)) }
        } else {
            Button(
                onClick = onStart,
                enabled = !busy,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            ) {
                ButtonLabel(
                    R.drawable.ic_play_arrow,
                    stringResource(if (failed) R.string.dashboard_start_again else R.string.dashboard_start),
                    large = true
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PanelCard(url: String, enabled: Boolean, running: Boolean, onOpenPanel: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceBright
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconBadge(
                    R.drawable.ic_dns,
                    shape = MaterialShapes.Cookie6Sided.toShape(),
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                    size = 48.dp
                )
                Text(stringResource(R.string.dashboard_panel_title), style = MaterialTheme.typography.titleLargeEmphasized)
            }
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
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                SelectionContainer {
                    Text(
                        url,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
            Button(
                onClick = onOpenPanel,
                enabled = enabled,
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.filledTonalButtonColors(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            ) { ButtonLabel(R.drawable.ic_open_in_new, stringResource(R.string.dashboard_panel_open)) }
            if (!running) {
                Text(
                    stringResource(R.string.dashboard_panel_requires_service),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun VersionLabel(version: String) {
    Text(
        version,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
