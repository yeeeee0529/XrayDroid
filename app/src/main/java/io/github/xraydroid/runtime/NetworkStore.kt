package io.github.xraydroid.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OutboundNetworkMode { SYSTEM, WIFI, CELLULAR, ETHERNET, VPN, OTHER }

data class NetworkOption(
    val mode: OutboundNetworkMode,
    val interfaceName: String,
    val addresses: List<String>,
    val dnsServers: List<String>,
    val handle: Long,
    val isValidated: Boolean,
    val isDefault: Boolean
)

data class InterfaceOption(
    val interfaceName: String,
    val addresses: List<String>,
    val dnsServers: List<String>,
    val mode: OutboundNetworkMode,
    val handle: Long?,
    val isUp: Boolean,
    val isValidated: Boolean,
    val isDefault: Boolean,
    val unavailableReason: String?
)

data class NetworkState(
    val selectedMode: OutboundNetworkMode = OutboundNetworkMode.SYSTEM,
    val options: List<NetworkOption> = emptyList(),
    val selectedOption: NetworkOption? = null,
    val requestingCellular: Boolean = false,
    val message: String = "",
    val selectedInterfaceName: String? = null,
    val interfaces: List<InterfaceOption> = emptyList()
)

object NetworkStore {
    private data class KernelInterface(
        val name: String,
        val addresses: List<String>,
        val isUp: Boolean,
        val isLoopback: Boolean
    )

    private data class VisibleNetwork(
        val network: Network,
        val capabilities: NetworkCapabilities?,
        val properties: LinkProperties?
    )

    private data class InterfaceSnapshot(
        val kernel: List<KernelInterface>,
        val visible: List<VisibleNetwork>,
        val defaultHandle: Long?
    )

    private data class InterfaceNetwork(
        val handle: Long,
        val mode: OutboundNetworkMode,
        val addresses: List<String>,
        val dnsServers: List<String>,
        val isValidated: Boolean,
        val isRestricted: Boolean,
        val isDefault: Boolean
    )

    private val handler = Handler(Looper.getMainLooper())
    private val snapshotExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "network-interface-snapshot").apply { isDaemon = true }
    }
    private val mutableState = MutableStateFlow(NetworkState())
    val state = mutableState.asStateFlow()
    private lateinit var connectivity: ConnectivityManager
    private lateinit var preferences: android.content.SharedPreferences
    private val networks = mutableMapOf<Long, Network>()
    private val capabilities = mutableMapOf<Long, NetworkCapabilities>()
    private val properties = mutableMapOf<Long, LinkProperties>()
    private val bindingFailures = mutableMapOf<Long, String>()
    private var kernelInterfaces = emptyList<KernelInterface>()
    private var defaultHandle: Long? = null
    private var cellularRequest: ConnectivityManager.NetworkCallback? = null
    private var initialized = false
    private var selectedMode = OutboundNetworkMode.SYSTEM
    private var selectedInterfaceName: String? = null
    private var requestingCellular = false
    private var message = ""

    @Volatile
    private var snapshotGeneration = 0L

    private val refreshTask = Runnable {
        val generation = snapshotGeneration
        snapshotExecutor.execute {
            if (generation != snapshotGeneration) return@execute
            val snapshot = readSnapshot()
            handler.post {
                // 回呼已收到新資料時，丟棄較早的背景快照。
                if (generation == snapshotGeneration) {
                    kernelInterfaces = snapshot.kernel
                    networks.clear()
                    capabilities.clear()
                    properties.clear()
                    snapshot.visible.forEach { visible ->
                        val handle = visible.network.networkHandle
                        networks[handle] = visible.network
                        visible.capabilities?.let { capabilities[handle] = it }
                        visible.properties?.let { properties[handle] = it }
                    }
                    bindingFailures.keys.retainAll(networks.keys)
                    defaultHandle = snapshot.defaultHandle
                    publish()
                }
            }
        }
    }

    fun initialize(context: Context) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "NetworkStore must initialize on the main thread" }
        if (initialized) return
        initialized = true
        connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        preferences = context.applicationContext.getSharedPreferences("outbound_network", Context.MODE_PRIVATE)
        selectedMode = runCatching {
            OutboundNetworkMode.valueOf(preferences.getString("mode", "SYSTEM") ?: "SYSTEM")
        }.getOrDefault(OutboundNetworkMode.SYSTEM)
        selectedInterfaceName = preferences.getString("interface_name", null)
        connectivity.registerNetworkCallback(
            NetworkRequest.Builder()
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        clearCapabilities()
                    } else {
                        removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                    }
                    addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                }
                .build(),
            callback(),
            handler
        )
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    defaultHandle = network.networkHandle
                    publishAndRefresh()
                }

                override fun onLost(network: Network) {
                    if (defaultHandle == network.networkHandle) defaultHandle = null
                    publishAndRefresh()
                }
            },
            handler
        )
        updateCellularRequest()
        publishAndRefresh()
    }

    fun select(mode: OutboundNetworkMode) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { select(mode) }
            return
        }
        check(initialized) { "NetworkStore is not initialized" }
        selectedMode = mode
        selectedInterfaceName = null
        preferences.edit {
            putString("mode", mode.name)
            remove("interface_name")
        }
        message = ""
        updateCellularRequest()
        publishAndRefresh()
    }

    fun selectInterface(name: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { selectInterface(name) }
            return
        }
        check(initialized) { "NetworkStore is not initialized" }
        require(name.isNotBlank()) { "Network interface name must not be blank" }
        val option = state.value.interfaces.firstOrNull { it.interfaceName == name }
        selectedMode = option?.mode ?: selectedMode.takeUnless { it == OutboundNetworkMode.SYSTEM }
            ?: OutboundNetworkMode.OTHER
        selectedInterfaceName = name
        preferences.edit {
            putString("mode", selectedMode.name)
            putString("interface_name", name)
        }
        message = ""
        updateCellularRequest()
        publishAndRefresh()
    }

    fun refreshInterfaces() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { refreshInterfaces() }
            return
        }
        check(initialized) { "NetworkStore is not initialized" }
        // 手動重新整理可重新嘗試綁定，網路回呼不清除此失敗紀錄。
        bindingFailures.clear()
        message = ""
        requestInterfaceRefresh()
    }

    fun reportBindingFailure(interfaceName: String, handle: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { reportBindingFailure(interfaceName, handle) }
            return
        }
        if (!initialized || handle !in networks) return
        val matches = state.value.interfaces.any { it.interfaceName == interfaceName && it.handle == handle }
        if (!matches) return
        bindingFailures[handle] = "目前無法綁定這個網路；可重新偵測後重試。"
        publish()
    }

    private fun requestInterfaceRefresh() {
        snapshotGeneration++
        handler.removeCallbacks(refreshTask)
        handler.postDelayed(refreshTask, 100)
    }

    private fun publishAndRefresh() {
        requestInterfaceRefresh()
        publish()
    }

    private fun callback() = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networks[network.networkHandle] = network
            publishAndRefresh()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            networks[network.networkHandle] = network
            capabilities[network.networkHandle] = networkCapabilities
            publishAndRefresh()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            networks[network.networkHandle] = network
            properties[network.networkHandle] = linkProperties
            publishAndRefresh()
        }

        override fun onLost(network: Network) {
            val handle = network.networkHandle
            networks.remove(handle)
            capabilities.remove(handle)
            properties.remove(handle)
            bindingFailures.remove(handle)
            publishAndRefresh()
        }
    }

    private fun updateCellularRequest() {
        if (selectedMode != OutboundNetworkMode.CELLULAR) {
            cellularRequest?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
            cellularRequest = null
            requestingCellular = false
            return
        }
        if (cellularRequest != null) return
        val requestCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (cellularRequest !== this) return
                message = ""
                publishAndRefresh()
            }

            override fun onUnavailable() {
                if (cellularRequest !== this) return
                cellularRequest = null
                requestingCellular = false
                message = "行動網路不可用；請確認行動數據已開啟。"
                publishAndRefresh()
            }
        }
        cellularRequest = requestCallback
        val requested = runCatching {
            connectivity.requestNetwork(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                requestCallback,
                handler
            )
        }
        if (requested.isFailure) {
            cellularRequest = null
            requestingCellular = false
            message = "無法取得行動網路；請檢查裝置的網路權限。"
            return
        }
        requestingCellular = true
        handler.postDelayed({
            if (cellularRequest === requestCallback && state.value.selectedOption == null) {
                requestingCellular = false
                message = "行動網路尚未可用；請確認行動數據已開啟。"
                publish()
            }
        }, 30_000)
    }

    private fun readSnapshot(): InterfaceSnapshot {
        val kernel = runCatching {
            val enumeration = NetworkInterface.getNetworkInterfaces()
            if (enumeration == null) {
                emptyList()
            } else {
                Collections.list(enumeration).mapNotNull { networkInterface ->
                    // 部分舊版 Android 的虛擬介面資訊可能拋出 NullPointerException。
                    runCatching {
                        val name = networkInterface.name
                        val addresses = runCatching {
                            networkInterface.interfaceAddresses.mapNotNull { address ->
                                address.address?.hostAddress?.let { "$it/${address.networkPrefixLength}" }
                            }
                        }.getOrElse {
                            Collections.list(networkInterface.inetAddresses).mapNotNull { it.hostAddress }
                        }
                        KernelInterface(
                            name,
                            addresses.distinct(),
                            runCatching { networkInterface.isUp }.getOrDefault(false),
                            runCatching { networkInterface.isLoopback }.getOrDefault(name == "lo")
                        )
                    }.getOrNull()
                }
            }
        }.getOrDefault(emptyList())

        @Suppress("DEPRECATION")
        val visible = runCatching { connectivity.allNetworks.toList() }.getOrDefault(emptyList()).map { network ->
            VisibleNetwork(
                network,
                runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull(),
                runCatching { connectivity.getLinkProperties(network) }.getOrNull()
            )
        }
        return InterfaceSnapshot(
            kernel,
            visible,
            runCatching { connectivity.activeNetwork?.networkHandle }.getOrNull()
        )
    }

    private fun modeFor(caps: NetworkCapabilities): OutboundNetworkMode = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> OutboundNetworkMode.VPN
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> OutboundNetworkMode.WIFI
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> OutboundNetworkMode.CELLULAR
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> OutboundNetworkMode.ETHERNET
        else -> OutboundNetworkMode.OTHER
    }

    private fun mergedInterfaces(): List<InterfaceOption> {
        val kernelByName = kernelInterfaces.associateBy { it.name }
        val networksByName = mutableMapOf<String, MutableList<InterfaceNetwork>>()
        networks.keys.forEach { handle ->
            val caps = capabilities[handle] ?: return@forEach
            val links = properties[handle] ?: return@forEach
            // 公開 API 不提供 stacked link 關聯；未確認歸屬的核心介面保持不可選。
            links.interfaceName?.let { name ->
                networksByName.getOrPut(name) { mutableListOf() }.add(
                    InterfaceNetwork(
                        handle,
                        modeFor(caps),
                        links.linkAddresses.map { it.toString() },
                        links.dnsServers.mapNotNull { it.hostAddress }.distinct(),
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED),
                        handle == defaultHandle
                    )
                )
            }
        }
        val names = (kernelByName.keys + networksByName.keys + listOfNotNull(selectedInterfaceName)).toSet()
        return names.map { name ->
            val kernel = kernelByName[name]
            val related = networksByName[name].orEmpty().distinctBy { it.handle }
            val network = related.singleOrNull()
            val isUp = kernel?.isUp ?: (network != null)
            val reason = when {
                kernel?.isLoopback == true -> "本機回送介面僅供裝置內部通訊，無法作為出站網路。"
                kernel == null && related.isEmpty() -> "指定介面目前不存在。"
                !isUp -> "介面目前未啟用。"
                related.size > 1 -> "此介面對應多個 Android 網路，無法安全指定。"
                network == null -> "未對應可供此 App 綁定的 Android 網路。"
                network.isRestricted -> "此 Android 網路限制一般 App 使用。"
                else -> bindingFailures[network.handle]
            }
            InterfaceOption(
                interfaceName = name,
                addresses = (kernel?.addresses.orEmpty() + related.flatMap { it.addresses }).distinct(),
                dnsServers = related.flatMap { it.dnsServers }.distinct(),
                mode = network?.mode ?: OutboundNetworkMode.OTHER,
                handle = network?.handle,
                isUp = isUp,
                isValidated = network?.isValidated == true,
                isDefault = network?.isDefault == true,
                unavailableReason = reason
            )
        }.sortedWith(compareBy<InterfaceOption> { it.mode.ordinal }.thenBy { it.interfaceName })
    }

    private fun publish() {
        val interfaces = mergedInterfaces()
        val options = interfaces.filter { it.handle != null && it.unavailableReason == null && it.isUp }
            .map { it.toNetworkOption() }
        val selected = resolveInterfaceSelection(selectedMode, selectedInterfaceName, interfaces)?.toNetworkOption()
        if (selected != null) requestingCellular = false
        mutableState.value = NetworkState(
            selectedMode = selectedMode,
            options = options,
            selectedOption = selected,
            requestingCellular = requestingCellular,
            message = message,
            selectedInterfaceName = selectedInterfaceName,
            interfaces = interfaces
        )
    }

    private fun InterfaceOption.toNetworkOption() = NetworkOption(
        mode,
        interfaceName,
        addresses,
        dnsServers,
        requireNotNull(handle),
        isValidated,
        isDefault
    )
}
