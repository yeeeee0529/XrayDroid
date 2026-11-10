package io.github.xraydroid.ui

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.xraydroid.R
import io.github.xraydroid.runtime.InterfaceOption
import io.github.xraydroid.runtime.NetworkState
import io.github.xraydroid.runtime.OutboundNetworkMode
import io.github.xraydroid.runtime.TextResource
import io.github.xraydroid.ui.components.SectionHeader
import io.github.xraydroid.ui.components.SegmentRow
import io.github.xraydroid.ui.components.SegmentedColumn
import io.github.xraydroid.ui.components.segmentShape

/** 網路介面選擇區塊；[titleRes] 為 null 時由所在頁面的頂端列提供標題與重新偵測。 */
@Composable
fun NetworkCard(
    state: NetworkState,
    appliedNetworkLabel: TextResource?,
    enabled: Boolean,
    onSelect: (OutboundNetworkMode) -> Unit,
    onSelectInterface: (String) -> Unit,
    onRefresh: () -> Unit,
    @StringRes titleRes: Int? = R.string.network_title,
    @StringRes descriptionRes: Int = R.string.network_description
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (titleRes != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(stringResource(titleRes), Modifier.weight(1f))
                IconButton(onClick = onRefresh) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.network_refresh))
                }
            }
        }
        Text(
            stringResource(descriptionRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        if (state.selectedInterfaceName == null && state.selectedMode != OutboundNetworkMode.SYSTEM &&
            state.selectedMode != OutboundNetworkMode.CELLULAR
        ) {
            NetworkDetail(stringResource(R.string.network_legacy_mode_notice), modifier = Modifier.padding(horizontal = 4.dp))
        }
        val count = state.interfaces.size + 2
        SegmentedColumn(Modifier.selectableGroup()) {
            NetworkSelectionRow(
                index = 0,
                count = count,
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
            state.interfaces.forEachIndexed { index, option ->
                InterfaceRow(
                    index = index + 1,
                    count = count,
                    option = option,
                    selected = state.selectedInterfaceName == option.interfaceName,
                    enabled = enabled,
                    onSelect = { onSelectInterface(option.interfaceName) }
                )
            }
            NetworkSelectionRow(
                index = count - 1,
                count = count,
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
        val unavailable = selected == null && !state.requestingCellular
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = if (unavailable) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = if (unavailable) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
        ) {
            Row(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(painterResource(if (unavailable) R.drawable.ic_error else R.drawable.ic_language), contentDescription = null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
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
                }
            }
        }
        Text(
            stringResource(R.string.network_vpn_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun InterfaceRow(index: Int, count: Int, option: InterfaceOption, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    val available = option.isUp && option.handle != null && option.unavailableReason == null
    NetworkSelectionRow(
        index = index,
        count = count,
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
private fun NetworkSelectionRow(
    index: Int,
    count: Int,
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    detail: @Composable () -> Unit
) {
    val shape = segmentShape(index, count)
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceBright,
        label = "networkRowColor"
    )
    Surface(
        modifier = Modifier.fillMaxWidth().clip(shape).selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onSelect
        ),
        shape = shape,
        color = color
    ) {
        SegmentRow(leading = { RadioButton(selected = selected, onClick = null, enabled = enabled) }, trailing = null) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
            )
            detail()
        }
    }
}

@Composable
private fun NetworkDetail(text: String, modifier: Modifier = Modifier, monospace: Boolean = false) {
    Text(
        text,
        modifier = modifier,
        style = if (monospace) {
            MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodySmall
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
