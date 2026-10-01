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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.xraydroid.runtime.InterfaceOption
import io.github.xraydroid.runtime.NetworkState
import io.github.xraydroid.runtime.OutboundNetworkMode

@Composable
fun NetworkCard(
    state: NetworkState,
    appliedNetworkLabel: String?,
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
                Text("出站網路", style = MaterialTheme.typography.titleLarge)
                TextButton(
                    onClick = onRefresh,
                    modifier = Modifier.semantics { contentDescription = "重新偵測" }
                ) { Text("重新偵測") }
            }
            Text(
                "選擇要使用的網路介面。切換會重新啟動運作中的核心，既有連線將中斷。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.selectedInterfaceName == null && state.selectedMode != OutboundNetworkMode.SYSTEM &&
                state.selectedMode != OutboundNetworkMode.CELLULAR
            ) {
                NetworkDetail("目前沿用先前的網路類型設定，尚未固定介面；選擇下方介面即可固定使用。")
            }
            Column(Modifier.selectableGroup()) {
                NetworkSelectionRow(
                    title = "跟隨系統",
                    selected = state.selectedInterfaceName == null && state.selectedMode == OutboundNetworkMode.SYSTEM,
                    enabled = enabled,
                    onSelect = { onSelect(OutboundNetworkMode.SYSTEM) }
                ) {
                    NetworkDetail(
                        state.interfaces.firstOrNull { it.isDefault }?.let { "目前預設介面：${it.interfaceName}" }
                            ?: "目前沒有預設網路"
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
                    title = "取得行動網路",
                    selected = state.selectedInterfaceName == null && state.selectedMode == OutboundNetworkMode.CELLULAR,
                    enabled = enabled,
                    onSelect = { onSelect(OutboundNetworkMode.CELLULAR) }
                ) {
                    NetworkDetail("向系統請求行動網路；連線後可選擇指定介面。")
                }
            }
            val selected = state.selectedOption
            val selectedInterface = state.interfaces.firstOrNull { it.interfaceName == state.selectedInterfaceName }
            val status = when {
                state.selectedInterfaceName == null && state.selectedMode == OutboundNetworkMode.SYSTEM ->
                    state.interfaces.firstOrNull { it.isDefault }?.let { "由系統決定路由，目前預設介面：${it.interfaceName}。" }
                        ?: "由系統決定路由，目前沒有預設網路。"
                selectedInterface?.unavailableReason != null -> "${selectedInterface.interfaceName}：${selectedInterface.unavailableReason}"
                selected != null && !selected.isValidated -> "所選網路已連線，尚未確認可連上網際網路。"
                selected != null -> "所選網路已連線。"
                state.requestingCellular -> "正在取得行動網路，請稍候。"
                state.message.isNotBlank() -> state.message
                else -> "所選介面不可用；不會自動改用其他網路。"
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
                if (appliedNetworkLabel == null) "核心尚未套用網路" else "核心目前使用：$appliedNetworkLabel",
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                "跟隨系統會沿用系統的 VPN（虛擬私人網路）；指定實體介面可能繞過 VPN。",
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
        val transport = when (option.mode) {
            OutboundNetworkMode.SYSTEM -> "系統網路"
            OutboundNetworkMode.WIFI -> "Wi-Fi"
            OutboundNetworkMode.CELLULAR -> "行動網路"
            OutboundNetworkMode.ETHERNET -> "乙太網路"
            OutboundNetworkMode.VPN -> "VPN（虛擬私人網路）"
            OutboundNetworkMode.OTHER -> "其他介面"
        }
        val status = when {
            option.unavailableReason != null -> option.unavailableReason
            !option.isUp -> "介面未啟用"
            option.handle == null -> "系統未提供可綁定網路"
            option.isValidated -> "可連上網際網路"
            else -> "已連線，網際網路未確認"
        }
        NetworkDetail("$transport · $status${if (option.isDefault) " · 系統預設" else ""}")
        if (option.addresses.isNotEmpty()) {
            NetworkDetail(option.addresses.joinToString("\n"), monospace = true)
        }
        if (option.dnsServers.isNotEmpty()) {
            NetworkDetail("DNS：${option.dnsServers.joinToString(", ")}", monospace = true)
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
