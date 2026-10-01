package io.github.xraydroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkSelectionTest {
    @Test
    fun exactInterfaceWinsOverDefaultAndTransportPreferences() {
        val wifi = option("wlan0", OutboundNetworkMode.WIFI, default = true)
        val vpn = option("tun0", OutboundNetworkMode.VPN)
        assertEquals(vpn, resolveInterfaceSelection(OutboundNetworkMode.WIFI, "tun0", listOf(wifi, vpn)))
    }

    @Test
    fun missingExplicitInterfaceNeverFallsBackToAnAvailableTransport() {
        val wifi = option("wlan0", OutboundNetworkMode.WIFI, default = true)
        assertNull(resolveInterfaceSelection(OutboundNetworkMode.WIFI, "wlan1", listOf(wifi)))
        assertNull(resolveInterfaceSelection(OutboundNetworkMode.SYSTEM, "tun0", listOf(wifi)))
    }

    @Test
    fun unavailableExplicitInterfaceNeverFallsBackToDefaultNetwork() {
        val wifi = option("wlan0", OutboundNetworkMode.WIFI, default = true)
        val rejected = listOf(
            option("tun0", OutboundNetworkMode.VPN).copy(handle = null),
            option("tun0", OutboundNetworkMode.VPN).copy(isUp = false),
            option("tun0", OutboundNetworkMode.VPN).copy(unavailableReason = "Unavailable")
        )
        rejected.forEach { vpn ->
            assertNull(resolveInterfaceSelection(OutboundNetworkMode.SYSTEM, "tun0", listOf(wifi, vpn)))
        }
    }

    @Test
    fun systemUsesTheBindableDefaultNetworkIncludingVpn() {
        val wifi = option("wlan0", OutboundNetworkMode.WIFI)
        val vpn = option("tun0", OutboundNetworkMode.VPN, default = true)
        assertEquals(vpn, resolveInterfaceSelection(OutboundNetworkMode.SYSTEM, null, listOf(wifi, vpn)))
        assertNull(resolveInterfaceSelection(OutboundNetworkMode.SYSTEM, null, listOf(wifi)))
    }

    @Test
    fun legacyTransportSelectionPrefersValidatedThenDefaultNetwork() {
        val default = option("wlan0", OutboundNetworkMode.WIFI, default = true, validated = false)
        val validated = option("wlan1", OutboundNetworkMode.WIFI)
        val cellular = option("rmnet_data0", OutboundNetworkMode.CELLULAR, default = true)
        assertEquals(validated, resolveInterfaceSelection(OutboundNetworkMode.WIFI, null, listOf(default, cellular, validated)))
        val validatedDefault = default.copy(isValidated = true)
        assertEquals(validatedDefault, resolveInterfaceSelection(OutboundNetworkMode.WIFI, null, listOf(validatedDefault, validated)))
    }

    @Test
    fun legacyTransportSelectionIgnoresUnbindableRows() {
        val down = option("eth0", OutboundNetworkMode.ETHERNET, default = true).copy(isUp = false)
        val usable = option("eth1", OutboundNetworkMode.ETHERNET)
        assertEquals(usable, resolveInterfaceSelection(OutboundNetworkMode.ETHERNET, null, listOf(down, usable)))
        assertNull(resolveInterfaceSelection(OutboundNetworkMode.CELLULAR, null, listOf(usable)))
    }

    private fun option(name: String, mode: OutboundNetworkMode, default: Boolean = false, validated: Boolean = true) = InterfaceOption(
        interfaceName = name,
        addresses = emptyList(),
        dnsServers = emptyList(),
        mode = mode,
        handle = 123L,
        isUp = true,
        isValidated = validated,
        isDefault = default,
        unavailableReason = null
    )
}
