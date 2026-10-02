package io.github.xraydroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.xraydroid.R
import io.github.xraydroid.runtime.InterfaceOption
import io.github.xraydroid.runtime.NetworkState
import io.github.xraydroid.runtime.OutboundNetworkMode
import io.github.xraydroid.runtime.TextResource

@Composable
fun NetworkCard(
    state: NetworkState,
    appliedNetworkLabel: TextResource?,
    enabled: Boolean,
    onSelect: (OutboundNetworkMode) -> Unit,
    onSelectInterface: (String) -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val refreshLabel = stringResource(R.string.network_refresh)
                Text(stringResource(R.string.network_title), style = MaterialTheme.typography.titleLarge)
                TextButton(
                    onClick = onRefresh,
                    modifier = Modifier.semantics { contentDescription = refreshLabel }
                ) { Text(refreshLabel) }
            }
            Text(
                stringResource(R.string.network_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.selectedInterfaceName == null && state.selectedMode != OutboundNetworkMode.SYSTEM &&
                state.selectedMode != OutboundNetworkMode.CELLULAR
            ) {
                NetworkDetail(stringResource(R.string.network_legacy_mode_notice))
            }
            Column(Modifier.selectableGroup()) {
                NetworkSelectionRow(
                    title = stringResource(R.string.network_follow_system),
                    selected = state.selectedInterfaceName == null && state.selectedMode == OutboundNetworkMode.SYSTEM,
                    enabled = enabled,
                    onSelect = { onSelect(OutboundNetworkMode.SYSTEM) }
                ) {
                    NetworkDetail(
                        state.interfaces.firstOrNull { it.isDefault }
                            ?.let { stringResource(R.string.network_default_interface, it.interfaceName) }
                            ?: stringResource(R.string.network_no_default_interface)
                    )
                }
                state.interfaces.forEach { option ->
                    InterfaceRow(
                        option = option,
                        selected = state.selectedInterfaceName == option.interfaceName,
                        enabled = enabled,
                        onSelect = { onSelectInterface(option.interfaceName) }
                    )
                }
                NetworkSelectionRow(
                    title = stringResource(R.string.network_request_cellular),
                    selected = state.selectedInterfaceName == null && state.selectedMode == OutboundNetworkMode.CELLULAR,
                    enabled = enabled,
                    onSelect = { onSelect(OutboundNetworkMode.CELLULAR) }
                ) {
                    NetworkDetail(stringResource(R.string.network_request_cellular_detail))
                }
            }
            val selected = state.selectedOption
            val selectedInterface = state.interfaces.firstOrNull { it.interfaceName == state.selectedInterfaceName }
            val message = state.message
            val reason = selectedInterface?.unavailableReason
            val status = when {
                state.selectedInterfaceName == null && state.selectedMode == OutboundNetworkMode.SYSTEM ->
                    state.interfaces.firstOrNull { it.isDefault }
                        ?.let { stringResource(R.string.network_status_follow_system, it.interfaceName) }
                        ?: stringResource(R.string.network_status_follow_system_no_default)
                reason != null -> stringResource(
                    R.string.network_status_interface_reason,
                    selectedInterface.interfaceName,
                    stringResource(reason)
                )
                selected != null && !selected.isValidated -> stringResource(R.string.network_status_connected_unvalidated)
                selected != null -> stringResource(R.string.network_status_connected)
                state.requestingCellular -> stringResource(R.string.network_status_requesting_cellular)
                message != null -> message.resolve()
                else -> stringResource(R.string.network_status_unavailable)
            }
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected == null && !state.requestingCellular) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Text(
                if (appliedNetworkLabel == null) {
                    stringResource(R.string.network_core_not_applied)
                } else {
                    stringResource(R.string.network_core_applied, appliedNetworkLabel.resolve())
                },
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                stringResource(R.string.network_vpn_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InterfaceRow(option: InterfaceOption, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val available = option.isUp && option.handle != null && option.unavailableReason == null
    NetworkSelectionRow(
        title = option.interfaceName,
        selected = selected,
        enabled = enabled && available,
        onSelect = onSelect
    ) {
        val transport = stringResource(
            when (option.mode) {
                OutboundNetworkMode.SYSTEM -> R.string.network_transport_system
                OutboundNetworkMode.WIFI -> R.string.network_transport_wifi
                OutboundNetworkMode.CELLULAR -> R.string.network_transport_cellular
                OutboundNetworkMode.ETHERNET -> R.string.network_transport_ethernet
                OutboundNetworkMode.VPN -> R.string.network_transport_vpn
                OutboundNetworkMode.OTHER -> R.string.network_transport_other
            }
        )
        val reason = option.unavailableReason
        val status = when {
            reason != null -> stringResource(reason)
            !option.isUp -> stringResource(R.string.network_interface_down)
            option.handle == null -> stringResource(R.string.network_interface_not_bindable)
            option.isValidated -> stringResource(R.string.network_interface_validated)
            else -> stringResource(R.string.network_interface_unvalidated)
        }
        val defaultSuffix = if (option.isDefault) stringResource(R.string.network_interface_default_suffix) else ""
        NetworkDetail("$transport · $status$defaultSuffix")
        if (option.addresses.isNotEmpty()) {
            NetworkDetail(option.addresses.joinToString("\n"), monospace = true)
        }
        if (option.dnsServers.isNotEmpty()) {
            NetworkDetail(stringResource(R.string.network_interface_dns, option.dnsServers.joinToString(", ")), monospace = true)
        }
    }
}

@Composable
private fun NetworkSelectionRow(title: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit, detail: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onSelect
        ).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            detail()
        }
    }
}

@Composable
private fun NetworkDetail(text: String, monospace: Boolean = false) {
    Text(
        text,
        style = if (monospace) {
            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodySmall
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
