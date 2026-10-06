package io.github.xraydroid.runtime

import io.github.xraydroid.R

fun resolveInterfaceSelection(
    selectedMode: OutboundNetworkMode,
    selectedInterfaceName: String?,
    interfaces: List<InterfaceOption>
): InterfaceOption? {
    val available = interfaces.filter {
        it.isUp && it.handle != null && it.unavailableReason == null
    }
    if (selectedInterfaceName != null) {
        return available.singleOrNull { it.interfaceName == selectedInterfaceName }
    }
    if (selectedMode == OutboundNetworkMode.SYSTEM) {
        return available.firstOrNull { it.isDefault }
    }
    return available.filter { it.mode == selectedMode }
        .sortedWith(
            compareByDescending<InterfaceOption> { it.isValidated }
                .thenByDescending { it.isDefault }
                .thenBy { it.interfaceName }
        )
        .firstOrNull()
}

internal fun NetworkState.followsSystem(): Boolean = selectedMode == OutboundNetworkMode.SYSTEM && selectedInterfaceName == null

internal fun NetworkState.networkLabel(): TextResource {
    val option = selectedOption
    val mode = if (followsSystem()) OutboundNetworkMode.SYSTEM else option?.mode ?: selectedMode
    val interfaceName = option?.interfaceName.orEmpty()
    return when (mode) {
        OutboundNetworkMode.SYSTEM -> TextResource(R.string.network_label_system)
        OutboundNetworkMode.WIFI -> TextResource(R.string.network_label_wifi, listOf(interfaceName))
        OutboundNetworkMode.CELLULAR -> TextResource(R.string.network_label_cellular, listOf(interfaceName))
        OutboundNetworkMode.ETHERNET -> TextResource(R.string.network_label_ethernet, listOf(interfaceName))
        OutboundNetworkMode.VPN -> TextResource(R.string.network_label_vpn, listOf(interfaceName))
        OutboundNetworkMode.OTHER -> TextResource(R.string.network_label_other, listOf(interfaceName))
    }
}
