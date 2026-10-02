package io.github.xraydroid.runtime

import androidx.annotation.StringRes
import io.github.xraydroid.R

enum class FrpConnectionPhase(@param:StringRes @get:StringRes val titleRes: Int) {
    UNKNOWN(R.string.frp_connection_unknown),
    CONNECTING(R.string.frp_connection_connecting),
    CONNECTED(R.string.frp_connection_connected),
    RETRYING(R.string.frp_connection_retrying),
    RECONNECTING(R.string.frp_connection_reconnecting)
}

data class FrpConnectionStatus(
    val phase: FrpConnectionPhase = FrpConnectionPhase.UNKNOWN,
    val error: String = "",
    val attempts: Int = 0
) {
    val detail: TextResource
        get() = TextResource(
            errorDetails[error] ?: when (phase) {
                FrpConnectionPhase.CONNECTED -> R.string.frp_connection_detail_connected
                FrpConnectionPhase.CONNECTING -> R.string.frp_connection_detail_connecting
                FrpConnectionPhase.RETRYING, FrpConnectionPhase.RECONNECTING -> R.string.frp_connection_detail_retrying
                FrpConnectionPhase.UNKNOWN -> R.string.frp_connection_detail_unknown
            }
        )
}

// 核心只輸出固定分類；未列出的分類一律視為無法辨識，不顯示任何內容。
internal val errorDetails: Map<String, Int> = mapOf(
    "dns" to R.string.frp_connection_error_dns,
    "refused" to R.string.frp_connection_error_refused,
    "timeout" to R.string.frp_connection_error_timeout,
    "unreachable" to R.string.frp_connection_error_unreachable,
    "tls" to R.string.frp_connection_error_tls,
    "authentication" to R.string.frp_connection_error_authentication,
    "login_rejected" to R.string.frp_connection_error_login_rejected,
    "closed" to R.string.frp_connection_error_closed,
    "connection" to R.string.frp_connection_error_connection
)

data class FrpRuntimeStatus(val connection: FrpConnectionStatus, val proxies: List<FrpProxyStatus>)

@StringRes
fun frpProxyStatusLabel(status: String): Int = when (status) {
    "running" -> R.string.frp_proxy_status_running
    "new" -> R.string.frp_proxy_status_new
    "wait start" -> R.string.frp_proxy_status_wait_start
    "start error" -> R.string.frp_proxy_status_start_error
    "check failed" -> R.string.frp_proxy_status_check_failed
    "closed" -> R.string.frp_proxy_status_closed
    else -> R.string.frp_proxy_status_unknown
}
