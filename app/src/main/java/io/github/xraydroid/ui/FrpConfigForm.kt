package io.github.xraydroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.xraydroid.runtime.FrpConfigDocument

private data class FrpField(
    val path: String,
    val label: String,
    val kind: String = "text",
    val choices: List<String> = emptyList(),
    val hint: String = "",
    val default: Boolean = false
)

private fun number(path: String, label: String, hint: String = "") = FrpField(path, label, "number", hint = hint)
private fun secret(path: String, label: String) = FrpField(path, label, "secret")
private fun list(path: String, label: String) = FrpField(path, label, "list", hint = "每行一筆；留空移除此設定")
private fun toggle(path: String, label: String, default: Boolean = false) = FrpField(path, label, "boolean", default = default)
private fun choice(path: String, label: String, vararg values: String) = FrpField(path, label, "choice", choices = values.toList())
private fun map(path: String, label: String) = FrpField(path, label, "map")

private val proxyTypes = listOf("tcp", "udp", "http", "https", "stcp", "sudp", "xtcp", "tcpmux")
private val visitorTypes = listOf("stcp", "sudp", "xtcp")
private val connectionFields = listOf(
    FrpField("serverAddr", "伺服器位址"),
    number("serverPort", "伺服器連接埠", "預設 7000"),
    FrpField("user", "使用者名稱"),
    FrpField("clientID", "用戶端識別碼")
)
private val transportFields = listOf(
    choice("transport.protocol", "連線協定", "tcp", "kcp", "quic", "websocket", "wss"),
    number("transport.dialServerTimeout", "連線逾時（秒）", "預設 10"),
    number("transport.dialServerKeepalive", "TCP 保活間隔（秒）", "預設 7200；負數停用"),
    FrpField("transport.connectServerLocalIP", "綁定本機 IP"),
    secret("transport.proxyURL", "代理伺服器 URL"),
    number("transport.poolCount", "預先建立連線數", "預設 1"),
    toggle("transport.tcpMux", "TCP 多路復用", true),
    number("transport.tcpMuxKeepaliveInterval", "TCP 多路復用心跳間隔（秒）", "預設 30"),
    number("transport.heartbeatInterval", "心跳間隔（秒）", "多路復用預設 -1；其他預設 30"),
    number("transport.heartbeatTimeout", "心跳逾時（秒）", "多路復用預設 -1；其他預設 90")
)
private val tlsFields = listOf(
    toggle("transport.tls.enable", "啟用 TLS", true),
    toggle("transport.tls.disableCustomTLSFirstByte", "停用 TLS 自訂首位元組", true),
    FrpField("transport.tls.serverName", "TLS 伺服器名稱"),
    FrpField("transport.tls.certFile", "用戶端憑證檔案路徑"),
    FrpField("transport.tls.keyFile", "用戶端私鑰檔案路徑"),
    FrpField("transport.tls.trustedCaFile", "信任 CA 憑證檔案路徑")
)
private val oidcFields = listOf(
    FrpField("auth.oidc.clientID", "OIDC 用戶端識別碼"),
    secret("auth.oidc.clientSecret", "OIDC 用戶端密鑰"),
    FrpField("auth.oidc.audience", "OIDC 對象"),
    FrpField("auth.oidc.scope", "OIDC 範圍"),
    FrpField("auth.oidc.tokenEndpointURL", "OIDC 權杖端點 URL"),
    FrpField("auth.oidc.trustedCaFile", "OIDC 信任 CA 檔案路徑"),
    toggle("auth.oidc.insecureSkipVerify", "OIDC 略過端點憑證驗證"),
    secret("auth.oidc.proxyURL", "OIDC 代理伺服器 URL"),
    map("auth.oidc.additionalEndpointParams", "OIDC 額外端點參數")
)
private val proxyTransportFields = listOf(
    toggle("transport.useEncryption", "加密"),
    toggle("transport.useCompression", "壓縮"),
    FrpField("transport.bandwidthLimit", "頻寬限制", hint = "例如 10MB 或 100KB；留空不限速"),
    choice("transport.bandwidthLimitMode", "頻寬限制位置", "client", "server"),
    choice("transport.proxyProtocolVersion", "PROXY 協定版本", "", "v1", "v2"),
    FrpField("loadBalancer.group", "負載平衡群組"),
    secret("loadBalancer.groupKey", "負載平衡群組密鑰"),
    choice("healthCheck.type", "健康檢查", "", "tcp", "http"),
    number("healthCheck.timeoutSeconds", "健康檢查逾時（秒）", "預設 3"),
    number("healthCheck.maxFailed", "健康檢查容許失敗次數", "預設 1"),
    number("healthCheck.intervalSeconds", "健康檢查間隔（秒）", "預設 10"),
    FrpField("healthCheck.path", "HTTP 健康檢查路徑"),
    FrpField("healthCheck.httpHeaders", "HTTP 健康檢查標頭", "headers"),
    map("metadatas", "代理中繼資料"),
    map("annotations", "代理註記")
)

/** 檢查表單涵蓋的欄位型別；不相容的 TOML 保留給文字模式修正。 */
fun canEditFrpForm(document: FrpConfigDocument): Boolean {
    val common = connectionFields + transportFields + tlsFields + oidcFields + listOf(
        choice("auth.method", "", "token", "oidc"), list("auth.additionalScopes", ""),
        FrpField("auth.token", ""), FrpField("natHoleStunServer", ""), FrpField("dnsServer", ""),
        number("udpPacketSize", ""), map("metadatas", ""),
        number("transport.quic.keepalivePeriod", ""), number("transport.quic.maxIdleTimeout", ""),
        number("transport.quic.maxIncomingStreams", "")
    )
    if (!validFieldShapes(common, document::get)) return false
    for (kind in listOf("proxies", "visitors")) {
        val value = document.get(kind) ?: continue
        if (value !is List<*> || value.any { it !is Map<*, *> }) return false
        for (rule in document.rules(kind)) {
            val type = rule["type"] as? String ?: return false
            if (type !in if (kind == "proxies") proxyTypes else visitorTypes) return false
            val fields = ruleFields(kind, type) + if (kind == "proxies") proxyTransportFields else emptyList()
            if (!validFieldShapes(fields) { readRule(rule, it) }) return false
        }
    }
    return true
}

private fun validFieldShapes(fields: List<FrpField>, get: (String) -> Any?): Boolean = fields.all { field ->
    val parts = field.path.split('.')
    val containersValid = (1 until parts.size).all { length ->
        val parent = get(parts.take(length).joinToString("."))
        parent == null || parent is Map<*, *>
    }
    val value = get(field.path)
    containersValid && (
        value == null || when (field.kind) {
            "number" -> value is Long || value is Int
            "boolean" -> value is Boolean
            "list" -> value is List<*> && value.all { it is String }
            "map" -> value is Map<*, *> && value.entries.all { it.key is String && it.value is String }
            "headers" -> value is List<*> && value.all { it is Map<*, *> && it["name"] is String && it["value"] is String }
            "choice" -> value is String && value in field.choices
            else -> value is String
        }
        )
}

@Stable
class FrpFormState {
    internal val rawInputs = mutableStateMapOf<String, String>()
    internal val errors = mutableStateMapOf<String, Boolean>()
    internal val expandedSections = mutableStateMapOf<String, Boolean>()
    internal val newRuleTypes = mutableStateMapOf<String, String>()
    val isValid: Boolean get() = errors.isEmpty()

    internal fun removeRuleInputs(prefix: String) {
        rawInputs.keys.filter { it.startsWith("$prefix/") }.forEach { rawInputs.remove(it) }
        errors.keys.filter { it.startsWith("$prefix/") }.forEach { errors.remove(it) }
        expandedSections.keys.filter { it == prefix || it.startsWith("$prefix/") }.forEach { expandedSections.remove(it) }
    }

    fun clear() {
        rawInputs.clear()
        errors.clear()
        expandedSections.clear()
        newRuleTypes.clear()
    }
}

@Composable
fun FrpConfigForm(
    document: FrpConfigDocument,
    enabled: Boolean,
    onChange: (FrpConfigDocument) -> Unit,
    onValidityChange: (Boolean) -> Unit = {},
    formState: FrpFormState = remember { FrpFormState() }
) {
    val valid = formState.isValid
    LaunchedEffect(valid) { onValidityChange(valid) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        frpConfigFormItems(document, enabled, onChange, formState)
    }
}

fun LazyListScope.frpConfigFormItems(
    document: FrpConfigDocument,
    enabled: Boolean,
    onChange: (FrpConfigDocument) -> Unit,
    formState: FrpFormState
) {
    // 草稿與展開狀態存在列表外，離開可見範圍不會遺失輸入。
    val valid = formState.isValid
    val edit: (String, Any?) -> Unit = { path, value -> onChange(document.set(path, value)) }
    item(key = "frp/common/0") {
        Text("留空使用 frpc 預設值。其他進階設定會保留在同一份 TOML 中。", style = MaterialTheme.typography.bodySmall)
    }
    item(key = "frp/common/1") {
        FrpSection(
            "基本設定",
            formState,
            "common/connection"
        ) { FrpFields(connectionFields, document::get, edit, enabled, "common", formState) }
    }
    item(key = "frp/common/2") {
        FrpSection("驗證", formState, "common/auth") {
            FrpFields(listOf(choice("auth.method", "驗證方式", "token", "oidc")), document::get, edit, enabled, "common", formState)
            if ((document.get("auth.method") as? String ?: "token") == "oidc") {
                FrpFields(oidcFields, document::get, edit, enabled, "common", formState)
            } else {
                FrpFields(listOf(secret("auth.token", "驗證權杖")), document::get, edit, enabled, "common", formState)
            }
            listOf("HeartBeats" to "驗證心跳", "NewWorkConns" to "驗證新工作連線").forEach { (scope, label) ->
                val scopes = (document.get("auth.additionalScopes") as? List<*>)?.filterIsInstance<String>().orEmpty()
                FrpSwitch(label, scope in scopes, enabled) { checked ->
                    edit("auth.additionalScopes", if (checked) (scopes + scope).distinct() else scopes - scope)
                }
            }
        }
    }
    item(key = "frp/common/3") {
        FrpSection("傳輸設定", formState, "common/transport") {
            FrpFields(transportFields, document::get, edit, enabled, "common", formState)
            if (document.get("transport.protocol") == "quic") {
                FrpFields(
                    listOf(
                        number("transport.quic.keepalivePeriod", "QUIC 保活間隔（秒）", "預設 10"),
                        number("transport.quic.maxIdleTimeout", "QUIC 閒置逾時（秒）", "預設 30"),
                        number("transport.quic.maxIncomingStreams", "QUIC 最大接收串流數", "預設 100000")
                    ),
                    document::get,
                    edit,
                    enabled,
                    "common",
                    formState
                )
            }
        }
    }
    item(key = "frp/common/4") {
        FrpSection("TLS 設定", formState, "common/tls") { FrpFields(tlsFields, document::get, edit, enabled, "common", formState) }
    }
    item(key = "frp/common/5") {
        FrpSection("其他設定", formState, "common/other") {
            FrpFields(
                listOf(
                    FrpField("natHoleStunServer", "STUN 伺服器", hint = "例如 stun.easyvoip.com:3478"),
                    FrpField("dnsServer", "DNS 伺服器"),
                    number("udpPacketSize", "UDP 最大封包長度", "預設 1500；需與伺服器一致"),
                    map("metadatas", "用戶端中繼資料")
                ),
                document::get,
                edit,
                enabled,
                "common",
                formState
            )
        }
    }
    listOf("proxies" to "轉發規則", "visitors" to "訪客規則").forEach { (kind, title) ->
        val rules = document.rules(kind)
        item(key = "frp/$kind/add") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                val newType = formState.newRuleTypes[kind] ?: if (kind == "proxies") "tcp" else "stcp"
                FrpChoice("新增規則協定", newType, if (kind == "proxies") proxyTypes else visitorTypes, enabled) {
                    formState.newRuleTypes[kind] = it
                }
                OutlinedButton(onClick = { onChange(document.addRule(kind, newType)) }, enabled = enabled && valid) { Text("新增$title") }
            }
        }
        itemsIndexed(
            rules,
            key = { index, _ -> "frp/$kind/${document.ruleId(kind, index)}" },
            contentType = { _, _ -> "frp-rule" }
        ) { index, rule ->
            val type = rule["type"] as? String ?: "tcp"
            val prefix = "$kind/${document.ruleId(kind, index)}"
            FrpSection("${rule["name"] ?: "未命名"} · ${type.uppercase()}", formState, prefix) {
                val get: (String) -> Any? = { path -> readRule(rule, path) }
                val set: (String, Any?) -> Unit = { path, value -> onChange(document.updateRule(kind, index, path, value)) }
                Text("規則協定為 ${type.uppercase()}；需要另一種協定時請新增規則。", style = MaterialTheme.typography.bodySmall)
                FrpFields(ruleFields(kind, type), get, set, enabled, prefix, formState)
                if (kind == "proxies") {
                    FrpSection("傳輸、負載平衡與健康檢查", formState, "$prefix/transport") {
                        FrpFields(proxyTransportFields, get, set, enabled, prefix, formState)
                    }
                }
                TextButton(onClick = {
                    formState.removeRuleInputs(prefix)
                    onChange(document.removeRule(kind, index))
                }, enabled = enabled) { Text("刪除此規則") }
            }
        }
    }
    if (!valid) {
        item(key = "frp/invalid") { Text("請修正所有無效欄位後再切換模式或儲存。", color = MaterialTheme.colorScheme.error) }
    }
}

private fun ruleFields(kind: String, type: String): List<FrpField> = buildList {
    add(FrpField("name", "規則名稱"))
    add(toggle("enabled", "啟用規則", true))
    if (kind == "visitors") {
        add(FrpField("serverName", "目標代理名稱"))
        add(FrpField("serverUser", "目標伺服器使用者"))
        add(secret("secretKey", "共用密鑰"))
        add(FrpField("bindAddr", "本機監聽位址", hint = "預設 127.0.0.1"))
        add(number("bindPort", "本機監聽連接埠", "STCP／XTCP 可用 -1 僅接收轉向連線"))
        add(toggle("transport.useEncryption", "加密"))
        add(toggle("transport.useCompression", "壓縮"))
        if (type == "xtcp") {
            add(choice("protocol", "穿透協定", "quic", "kcp"))
            add(toggle("keepTunnelOpen", "保持隧道開啟"))
            add(number("maxRetriesAnHour", "每小時重試上限", "預設 8"))
            add(number("minRetryInterval", "最短重試間隔（秒）", "預設 90"))
            add(FrpField("fallbackTo", "備援訪客名稱"))
            add(number("fallbackTimeoutMs", "備援切換逾時（毫秒）", "預設 1000"))
            add(toggle("natTraversal.disableAssistedAddrs", "停用輔助穿透位址"))
        }
    } else {
        add(FrpField("localIP", "本機服務位址", hint = "預設 127.0.0.1"))
        add(number("localPort", "本機服務連接埠"))
        when (type) {
            "tcp", "udp" -> add(number("remotePort", "遠端連接埠", "0 由伺服器自動分配"))
            "http", "https", "tcpmux" -> {
                add(list("customDomains", "自訂網域"))
                add(FrpField("subdomain", "子網域"))
                if (type != "https") {
                    add(FrpField("httpUser", "HTTP 驗證使用者"))
                    add(secret("httpPassword", "HTTP 驗證密碼"))
                    add(FrpField("routeByHTTPUser", "依 HTTP 使用者路由"))
                }
                if (type == "http") {
                    add(list("locations", "路徑"))
                    add(FrpField("hostHeaderRewrite", "覆寫 Host 標頭"))
                    add(map("requestHeaders.set", "要求標頭"))
                    add(map("responseHeaders.set", "回應標頭"))
                }
                if (type == "tcpmux") add(choice("multiplexer", "多路復用器", "httpconnect"))
            }
            "stcp", "sudp", "xtcp" -> {
                add(secret("secretKey", "共用密鑰"))
                add(list("allowUsers", "允許的使用者"))
                if (type == "xtcp") add(toggle("natTraversal.disableAssistedAddrs", "停用輔助穿透位址"))
            }
        }
    }
}

private fun readRule(rule: Map<String, Any>, path: String): Any? {
    var value: Any? = rule
    path.split('.').forEach { part -> value = (value as? Map<*, *>)?.get(part) }
    return value
}

@Composable
private fun FrpSection(title: String, state: FrpFormState, id: String, content: @Composable () -> Unit) {
    val expanded = state.expandedSections[id] ?: false
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { state.expandedSections[id] = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text("${if (expanded) "▾" else "▸"} $title", style = MaterialTheme.typography.titleMedium)
            }
            if (expanded) content()
        }
    }
}

@Composable
private fun FrpFields(
    fields: List<FrpField>,
    get: (String) -> Any?,
    set: (String, Any?) -> Unit,
    enabled: Boolean,
    prefix: String,
    formState: FrpFormState
) {
    fields.forEach { field ->
        key(field.path) {
            val value = get(field.path)
            when (field.kind) {
                "boolean" -> FrpSwitch(field.label, value as? Boolean ?: field.default, enabled) { set(field.path, it) }
                "choice" -> FrpChoice(field.label, value as? String ?: "", field.choices, enabled) {
                    set(field.path, it.ifEmpty { null })
                }
                "map" -> FrpMap(field.label, value, enabled, "$prefix/${field.path}", formState) { set(field.path, it) }
                "headers" -> FrpHeaders(field.label, value, enabled, "$prefix/${field.path}", formState) { set(field.path, it) }
                else -> {
                    val id = "$prefix/${field.path}"
                    val text = if (field.kind == "list") (value as? List<*>)?.joinToString("\n").orEmpty() else value?.toString().orEmpty()
                    var revealed by remember { mutableStateOf(false) }
                    OutlinedTextField(
                        value = formState.rawInputs[id] ?: text,
                        onValueChange = { input ->
                            when (field.kind) {
                                "number" -> {
                                    val parsed = input.toLongOrNull()
                                    if (input.isBlank() || parsed != null) {
                                        formState.rawInputs.remove(id)
                                        formState.errors.remove(id)
                                        set(field.path, parsed)
                                    } else {
                                        formState.rawInputs[id] = input
                                        formState.errors[id] = true
                                    }
                                }
                                "list" -> {
                                    formState.rawInputs[id] = input
                                    set(field.path, input.lines().filter { it.isNotBlank() }.takeIf { it.isNotEmpty() })
                                }
                                else -> set(field.path, input.ifEmpty { null })
                            }
                        },
                        label = { Text(field.label) },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        isError = id in formState.errors,
                        supportingText = {
                            if (id in formState.errors) Text("請輸入有效整數") else if (field.hint.isNotEmpty()) Text(field.hint)
                        },
                        singleLine = field.kind != "list",
                        visualTransformation = if (field.kind == "secret" && !revealed) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        trailingIcon = if (field.kind == "secret") {
                            { TextButton(onClick = { revealed = !revealed }, enabled = enabled) { Text(if (revealed) "隱藏" else "顯示") } }
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FrpSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun FrpChoice(label: String, value: String, choices: List<String>, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(value.ifEmpty { "預設" })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { item ->
                DropdownMenuItem(text = { Text(item.ifEmpty { "停用" }) }, onClick = {
                    expanded = false
                    onChange(item)
                })
            }
        }
    }
}

@Composable
private fun FrpMap(
    label: String,
    value: Any?,
    enabled: Boolean,
    prefix: String,
    state: FrpFormState,
    onChange: (Map<String, Any>?) -> Unit
) {
    val entries = (value as? Map<*, *>)?.entries?.map { it.key.toString() to it.value.toString() }.orEmpty()
    FrpPairs(label, entries, enabled, prefix, state) { pairs -> onChange(pairs.toMap().takeIf { it.isNotEmpty() }) }
}

@Composable
private fun FrpHeaders(
    label: String,
    value: Any?,
    enabled: Boolean,
    prefix: String,
    state: FrpFormState,
    onChange: (List<Map<String, String>>?) -> Unit
) {
    val entries = (value as? List<*>)?.mapNotNull { entry ->
        (entry as? Map<*, *>)?.let { it["name"].toString() to it["value"].toString() }
    }.orEmpty()
    FrpPairs(label, entries, enabled, prefix, state) { pairs ->
        onChange(pairs.map { (name, content) -> mapOf("name" to name, "value" to content) }.takeIf { it.isNotEmpty() })
    }
}

@Composable
private fun FrpPairs(
    label: String,
    entries: List<Pair<String, String>>,
    enabled: Boolean,
    prefix: String,
    state: FrpFormState,
    onChange: (List<Pair<String, String>>) -> Unit
) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    entries.forEachIndexed { index, (name, value) ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val id = "$prefix/$index/name"
            OutlinedTextField(
                state.rawInputs[id] ?: name,
                { updated ->
                    if (updated.isBlank() || entries.withIndex().any { it.index != index && it.value.first == updated }) {
                        state.rawInputs[id] = updated
                        state.errors[id] = true
                    } else {
                        state.rawInputs.remove(id)
                        state.errors.remove(id)
                        onChange(entries.toMutableList().also { it[index] = updated to value })
                    }
                },
                label = { Text("名稱") },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = id in state.errors,
                supportingText = { if (id in state.errors) Text("名稱不可空白或重複") }
            )
            OutlinedTextField(value, { updated ->
                onChange(entries.toMutableList().also { it[index] = name to updated })
            }, label = { Text("值") }, enabled = enabled, modifier = Modifier.fillMaxWidth(), singleLine = true)
            TextButton(onClick = {
                onChange(entries.filterIndexed { position, _ -> position != index })
            }, enabled = enabled && state.isValid) { Text("刪除$name") }
        }
    }
    OutlinedButton(onClick = {
        var name = "key"
        var suffix = 1
        while (entries.any { it.first == name }) name = "key${suffix++}"
        onChange(entries + (name to ""))
    }, enabled = enabled && state.isValid) { Text("新增$label") }
}
