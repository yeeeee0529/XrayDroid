package io.github.xraydroid.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OutboundNetworkMode { SYSTEM, WIFI, CELLULAR, ETHERNET }

data class NetworkOption(
    val mode: OutboundNetworkMode,
    val interfaceName: String,
    val addresses: List<String>,
    val dnsServers: List<String>,
    val handle: Long,
    val isValidated: Boolean,
    val isDefault: Boolean
)

data class NetworkState(
    val selectedMode: OutboundNetworkMode = OutboundNetworkMode.SYSTEM,
    val options: List<NetworkOption> = emptyList(),
    val selectedOption: NetworkOption? = null,
    val requestingCellular: Boolean = false,
    val message: String = ""
)

object NetworkStore {
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(NetworkState())
    val state = mutableState.asStateFlow()
    private lateinit var connectivity: ConnectivityManager
    private lateinit var preferences: android.content.SharedPreferences
    private val networks = mutableMapOf<Long, Network>()
    private val capabilities = mutableMapOf<Long, NetworkCapabilities>()
    private val properties = mutableMapOf<Long, LinkProperties>()
    private var defaultHandle: Long? = null
    private var cellularRequest: ConnectivityManager.NetworkCallback? = null
    private var initialized = false
    private var selectedMode = OutboundNetworkMode.SYSTEM
    private var requestingCellular = false
    private var message = ""

    fun initialize(context: Context) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "NetworkStore must initialize on the main thread" }
        if (initialized) return
        initialized = true
        connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        preferences = context.applicationContext.getSharedPreferences("outbound_network", Context.MODE_PRIVATE)
        val selected = runCatching {
            OutboundNetworkMode.valueOf(preferences.getString("mode", "SYSTEM") ?: "SYSTEM")
        }.getOrDefault(OutboundNetworkMode.SYSTEM)
        selectedMode = selected
        // 初始快照之後由回呼更新，所有可變資料都限定在主執行緒。
        @Suppress("DEPRECATION")
        val initialNetworks = connectivity.allNetworks
        initialNetworks.forEach { network ->
            networks[network.networkHandle] = network
            connectivity.getNetworkCapabilities(network)?.let { capabilities[network.networkHandle] = it }
            connectivity.getLinkProperties(network)?.let { properties[network.networkHandle] = it }
        }
        defaultHandle = connectivity.activeNetwork?.networkHandle
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            callback(),
            handler
        )
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    defaultHandle = network.networkHandle
                    publish()
                }

                override fun onLost(network: Network) {
                    if (defaultHandle == network.networkHandle) defaultHandle = null
                    publish()
                }
            },
            handler
        )
        updateCellularRequest()
        publish()
    }

    fun select(mode: OutboundNetworkMode) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { select(mode) }
            return
        }
        check(initialized) { "NetworkStore is not initialized" }
        if (mode == selectedMode) {
            if (mode == OutboundNetworkMode.CELLULAR && cellularRequest == null) {
                message = ""
                updateCellularRequest()
                publish()
            }
            return
        }
        preferences.edit { putString("mode", mode.name) }
        selectedMode = mode
        message = ""
        updateCellularRequest()
        publish()
    }

    private fun callback() = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networks[network.networkHandle] = network
            publish()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            networks[network.networkHandle] = network
            capabilities[network.networkHandle] = networkCapabilities
            publish()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            networks[network.networkHandle] = network
            properties[network.networkHandle] = linkProperties
            publish()
        }

        override fun onLost(network: Network) {
            networks.remove(network.networkHandle)
            capabilities.remove(network.networkHandle)
            properties.remove(network.networkHandle)
            publish()
        }
    }

    private fun updateCellularRequest() {
        cellularRequest?.let { connectivity.unregisterNetworkCallback(it) }
        cellularRequest = null
        requestingCellular = false
        if (selectedMode != OutboundNetworkMode.CELLULAR) return
        val requestCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 網路資訊由一般網路回呼提供；此回呼只維持行動網路請求。
                if (cellularRequest !== this) return
                message = ""
                publish()
            }

            override fun onUnavailable() {
                if (cellularRequest !== this) return
                cellularRequest = null
                requestingCellular = false
                message = "行動網路不可用；請確認行動數據已開啟。"
                publish()
            }
        }
        cellularRequest = requestCallback
        connectivity.requestNetwork(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            requestCallback,
            handler
        )
        requestingCellular = true
        // 保留請求，讓暫時關閉的行動網路恢復後仍能自動取得連線。
        handler.postDelayed({
            if (cellularRequest === requestCallback && state.value.selectedOption == null) {
                requestingCellular = false
                message = "行動網路尚未可用；請確認行動數據已開啟。"
                publish()
            }
        }, 30_000)
    }

    private fun publish() {
        val options = networks.keys.mapNotNull { handle ->
            val caps = capabilities[handle] ?: return@mapNotNull null
            val links = properties[handle] ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            val mode = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> OutboundNetworkMode.SYSTEM
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> OutboundNetworkMode.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> OutboundNetworkMode.CELLULAR
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> OutboundNetworkMode.ETHERNET
                else -> OutboundNetworkMode.SYSTEM
            }
            NetworkOption(
                mode = mode,
                interfaceName = links.interfaceName ?: "—",
                addresses = links.linkAddresses.map { it.toString() },
                dnsServers = links.dnsServers.mapNotNull { it.hostAddress },
                handle = handle,
                isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                isDefault = handle == defaultHandle
            )
        }.sortedWith(compareBy<NetworkOption> { it.mode.ordinal }.thenBy { it.interfaceName })
        val selected = if (selectedMode == OutboundNetworkMode.SYSTEM) {
            options.firstOrNull { it.isDefault }
        } else {
            options.filter {
                it.mode == selectedMode && capabilities[it.handle]?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_NOT_VPN
                ) == true
            }
                .sortedWith(compareByDescending<NetworkOption> { it.isValidated }.thenByDescending { it.isDefault })
                .firstOrNull()
        }
        if (selected != null) requestingCellular = false
        mutableState.value = NetworkState(
            selectedMode = selectedMode,
            options = options,
            selectedOption = selected,
            requestingCellular = requestingCellular,
            message = message
        )
    }
}
