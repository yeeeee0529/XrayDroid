package io.github.xraydroid.runtime

enum class FrpConnectionPhase(val title: String) {
    UNKNOWN("連線狀態無法取得"),
    CONNECTING("正在連線至 frps"),
    CONNECTED("已連線至 frps"),
    RETRYING("連線失敗，重試中"),
    RECONNECTING("已斷線，重新連線中")
}

data class FrpConnectionStatus(
    val phase: FrpConnectionPhase = FrpConnectionPhase.UNKNOWN,
    val error: String = "",
    val attempts: Int = 0
) {
    val detail: String
        get() = errorDetails[error] ?: when (phase) {
            FrpConnectionPhase.CONNECTED -> "已成功登入 frps；轉發是否可用仍需確認代理狀態。"
            FrpConnectionPhase.CONNECTING -> "尚未確認登入成功，正在等待 frps 回應。"
            FrpConnectionPhase.RETRYING, FrpConnectionPhase.RECONNECTING -> "frpc 會自動重試，無需手動重新啟動。"
            FrpConnectionPhase.UNKNOWN -> "暫時無法取得核心連線狀態，尚不能確認是否已連線。"
        }
}

// 核心只輸出固定分類；未列出的分類一律視為無法辨識，不顯示任何內容。
internal val errorDetails = mapOf(
    "dns" to "無法解析伺服器位址，請檢查位址與 DNS。",
    "refused" to "伺服器拒絕連線，請確認 frps 已啟動且連接埠正確。",
    "timeout" to "連線逾時，請檢查網路、伺服器連接埠與防火牆。",
    "unreachable" to "無法到達伺服器，請檢查系統網路與 VPN。",
    "tls" to "TLS 連線或憑證驗證失敗，請檢查 TLS 設定與憑證。",
    "authentication" to "無法準備登入驗證，請檢查 Token／OIDC 與權杖來源。",
    "login_rejected" to "frps 拒絕登入，請檢查驗證設定及伺服器的登入限制。",
    "closed" to "伺服器在登入完成前關閉連線。通常是 TCP 多路復用（tcpMux）與伺服器設定不一致，或雙方版本不相容；請先確認兩邊的 tcpMux 設定相同。",
    "connection" to "連線或交握失敗，請檢查位址、連接埠、傳輸協定與驗證設定。"
)

data class FrpRuntimeStatus(val connection: FrpConnectionStatus, val proxies: List<FrpProxyStatus>)

fun frpProxyStatusLabel(status: String): String = when (status) {
    "running" -> "代理已啟用"
    "new" -> "尚未註冊"
    "wait start" -> "等待註冊"
    "start error" -> "代理啟動失敗，請檢查規則、遠端連接埠與伺服器限制"
    "check failed" -> "健康檢查失敗"
    "closed" -> "已關閉"
    else -> "代理狀態未知"
}
