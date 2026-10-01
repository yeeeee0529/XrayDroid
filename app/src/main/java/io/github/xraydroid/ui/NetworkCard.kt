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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.xraydroid.runtime.NetworkOption
import io.github.xraydroid.runtime.NetworkState
import io.github.xraydroid.runtime.OutboundNetworkMode

@Composable
fun NetworkCard(state: NetworkState, appliedNetworkLabel: String?, enabled: Boolean, onSelect: (OutboundNetworkMode) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("出站網路", style = MaterialTheme.typography.titleLarge)
            Text(
                "切換會重新啟動運作中的核心，既有連線將中斷。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(Modifier.selectableGroup()) {
                OutboundNetworkMode.entries.forEach { mode ->
                    NetworkModeRow(
                        mode = mode,
                        selected = state.selectedMode == mode,
                        enabled = enabled,
                        options = if (mode == OutboundNetworkMode.SYSTEM) {
                            state.options.filter { it.isDefault }
                        } else {
                            state.options.filter { it.mode == mode }
                        },
                        onSelect = { onSelect(mode) }
                    )
                }
            }
            val selected = state.selectedOption
            val status = when {
                selected != null && !selected.isValidated -> "所選網路已連線，尚未確認可連上網際網路。"
                selected != null -> "所選網路已連線。"
                state.requestingCellular -> "正在取得行動網路，請稍候。"
                state.message.isNotBlank() -> state.message
                state.selectedMode == OutboundNetworkMode.SYSTEM -> "目前沒有預設網路。"
                else -> "所選網路不可用；不會自動改用其他網路。"
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
                "跟隨系統會沿用系統的 VPN（虛擬私人網路）；指定實體網路可能繞過 VPN。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NetworkModeRow(
    mode: OutboundNetworkMode,
    selected: Boolean,
    enabled: Boolean,
    options: List<NetworkOption>,
    onSelect: () -> Unit
) {
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
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when (mode) {
                    OutboundNetworkMode.SYSTEM -> "跟隨系統"
                    OutboundNetworkMode.WIFI -> "Wi-Fi"
                    OutboundNetworkMode.CELLULAR -> "行動網路"
                    OutboundNetworkMode.ETHERNET -> "乙太網路"
                },
                style = MaterialTheme.typography.titleMedium
            )
            if (options.isEmpty()) {
                Text(
                    if (mode == OutboundNetworkMode.CELLULAR) "尚無連線；選取後會請求行動網路" else "目前無連線",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            options.forEach { option ->
                Text(
                    "${option.interfaceName} · ${if (option.isValidated) "可連上網際網路" else "已連線，網際網路未確認"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (option.dnsServers.isNotEmpty()) {
                    Text(
                        "DNS：${option.dnsServers.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (option.addresses.isNotEmpty()) {
                    Text(
                        option.addresses.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
