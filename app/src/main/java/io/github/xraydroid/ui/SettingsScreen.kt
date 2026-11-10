package io.github.xraydroid.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.xraydroid.R
import io.github.xraydroid.runtime.NetworkState
import io.github.xraydroid.runtime.OutboundNetworkMode
import io.github.xraydroid.runtime.ServerPhase
import io.github.xraydroid.runtime.ServerState
import io.github.xraydroid.ui.components.AppPage
import io.github.xraydroid.ui.components.IconBadge
import io.github.xraydroid.ui.components.SegmentedColumn
import io.github.xraydroid.ui.components.SegmentedItem

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenNetwork: () -> Unit, onOpenFrp: () -> Unit) {
    AppPage(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        backLabel = stringResource(R.string.nav_back_home)
    ) {
        item {
            SegmentedColumn {
                SettingsEntry(
                    0,
                    R.drawable.ic_language,
                    R.string.settings_network_title,
                    R.string.settings_network_description,
                    onOpenNetwork
                )
                SettingsEntry(1, R.drawable.ic_swap_horiz, R.string.settings_frp_title, R.string.settings_frp_description, onOpenFrp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsEntry(index: Int, @DrawableRes icon: Int, @StringRes title: Int, @StringRes description: Int, onClick: () -> Unit) {
    SegmentedItem(
        index = index,
        count = 2,
        headline = stringResource(title),
        supporting = stringResource(description),
        onClick = onClick,
        leading = {
            IconBadge(
                icon,
                shape = (if (index == 0) MaterialShapes.Cookie9Sided else MaterialShapes.Clover4Leaf).toShape(),
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer
            )
        },
        trailing = {
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
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
    AppPage(
        title = stringResource(R.string.network_title),
        onBack = onBack,
        backLabel = stringResource(R.string.nav_back_settings),
        actions = {
            IconButton(onClick = onRefreshInterfaces) {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.network_refresh))
            }
        }
    ) {
        item {
            // 頁面標題與重新偵測已在頂端列，卡片不再重複。
            NetworkCard(
                state = networkState,
                appliedNetworkLabel = serverState.outboundNetworkLabel,
                enabled = !busy,
                onSelect = onSelectNetwork,
                onSelectInterface = onSelectInterface,
                onRefresh = onRefreshInterfaces,
                titleRes = null
            )
        }
    }
}
