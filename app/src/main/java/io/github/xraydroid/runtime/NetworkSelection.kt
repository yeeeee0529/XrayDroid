package io.github.xraydroid.runtime

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
