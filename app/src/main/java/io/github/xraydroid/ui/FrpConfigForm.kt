package io.github.xraydroid.ui

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.xraydroid.R
import io.github.xraydroid.runtime.FrpConfigDocument

private data class FrpField(
    val path: String,
    @param:StringRes @get:StringRes val label: Int,
    val kind: String = "text",
    val choices: List<String> = emptyList(),
    @param:StringRes @get:StringRes val hint: Int? = null,
    val default: Boolean = false
)

private fun number(path: String, @StringRes label: Int, @StringRes hint: Int? = null) = FrpField(path, label, "number", hint = hint)
private fun secret(path: String, @StringRes label: Int) = FrpField(path, label, "secret")
private fun list(path: String, @StringRes label: Int) = FrpField(path, label, "list", hint = R.string.frp_form_list_hint)
private fun toggle(path: String, @StringRes label: Int, default: Boolean = false) = FrpField(path, label, "boolean", default = default)
private fun choice(path: String, @StringRes label: Int, vararg values: String) = FrpField(path, label, "choice", choices = values.toList())
private fun map(path: String, @StringRes label: Int) = FrpField(path, label, "map")

private val proxyTypes = listOf("tcp", "udp", "http", "https", "stcp", "sudp", "xtcp", "tcpmux")
private val visitorTypes = listOf("stcp", "sudp", "xtcp")
private val connectionFields = listOf(
    FrpField("serverAddr", R.string.frp_form_field_server_addr),
    number("serverPort", R.string.frp_form_field_server_port, R.string.frp_form_hint_server_port),
    FrpField("user", R.string.frp_form_field_user),
    FrpField("clientID", R.string.frp_form_field_client_id)
)
private val transportFields = listOf(
    choice("transport.protocol", R.string.frp_form_field_transport_protocol, "tcp", "kcp", "quic", "websocket", "wss"),
    number("transport.dialServerTimeout", R.string.frp_form_field_dial_timeout, R.string.frp_form_hint_dial_timeout),
    number("transport.dialServerKeepalive", R.string.frp_form_field_dial_keepalive, R.string.frp_form_hint_dial_keepalive),
    FrpField("transport.connectServerLocalIP", R.string.frp_form_field_connect_local_ip),
    secret("transport.proxyURL", R.string.frp_form_field_proxy_url),
    number("transport.poolCount", R.string.frp_form_field_pool_count, R.string.frp_form_hint_pool_count),
    toggle("transport.tcpMux", R.string.frp_form_field_tcp_mux, true),
    number("transport.tcpMuxKeepaliveInterval", R.string.frp_form_field_tcp_mux_keepalive, R.string.frp_form_hint_tcp_mux_keepalive),
    number("transport.heartbeatInterval", R.string.frp_form_field_heartbeat_interval, R.string.frp_form_hint_heartbeat_interval),
    number("transport.heartbeatTimeout", R.string.frp_form_field_heartbeat_timeout, R.string.frp_form_hint_heartbeat_timeout)
)
private val tlsFields = listOf(
    toggle("transport.tls.enable", R.string.frp_form_field_tls_enable, true),
    toggle("transport.tls.disableCustomTLSFirstByte", R.string.frp_form_field_tls_disable_first_byte, true),
    FrpField("transport.tls.serverName", R.string.frp_form_field_tls_server_name),
    FrpField("transport.tls.certFile", R.string.frp_form_field_tls_cert_file),
    FrpField("transport.tls.keyFile", R.string.frp_form_field_tls_key_file),
    FrpField("transport.tls.trustedCaFile", R.string.frp_form_field_tls_trusted_ca)
)
private val oidcFields = listOf(
    FrpField("auth.oidc.clientID", R.string.frp_form_field_oidc_client_id),
    secret("auth.oidc.clientSecret", R.string.frp_form_field_oidc_client_secret),
    FrpField("auth.oidc.audience", R.string.frp_form_field_oidc_audience),
    FrpField("auth.oidc.scope", R.string.frp_form_field_oidc_scope),
    FrpField("auth.oidc.tokenEndpointURL", R.string.frp_form_field_oidc_token_endpoint),
    FrpField("auth.oidc.trustedCaFile", R.string.frp_form_field_oidc_trusted_ca),
    toggle("auth.oidc.insecureSkipVerify", R.string.frp_form_field_oidc_skip_verify),
    secret("auth.oidc.proxyURL", R.string.frp_form_field_oidc_proxy_url),
    map("auth.oidc.additionalEndpointParams", R.string.frp_form_field_oidc_endpoint_params)
)
private val proxyTransportFields = listOf(
    toggle("transport.useEncryption", R.string.frp_form_field_use_encryption),
    toggle("transport.useCompression", R.string.frp_form_field_use_compression),
    FrpField("transport.bandwidthLimit", R.string.frp_form_field_bandwidth_limit, hint = R.string.frp_form_hint_bandwidth_limit),
    choice("transport.bandwidthLimitMode", R.string.frp_form_field_bandwidth_limit_mode, "client", "server"),
    choice("transport.proxyProtocolVersion", R.string.frp_form_field_proxy_protocol_version, "", "v1", "v2"),
    FrpField("loadBalancer.group", R.string.frp_form_field_load_balancer_group),
    secret("loadBalancer.groupKey", R.string.frp_form_field_load_balancer_key),
    choice("healthCheck.type", R.string.frp_form_field_health_check_type, "", "tcp", "http"),
    number("healthCheck.timeoutSeconds", R.string.frp_form_field_health_check_timeout, R.string.frp_form_hint_health_check_timeout),
    number("healthCheck.maxFailed", R.string.frp_form_field_health_check_max_failed, R.string.frp_form_hint_health_check_max_failed),
    number("healthCheck.intervalSeconds", R.string.frp_form_field_health_check_interval, R.string.frp_form_hint_health_check_interval),
    FrpField("healthCheck.path", R.string.frp_form_field_health_check_path),
    FrpField("healthCheck.httpHeaders", R.string.frp_form_field_health_check_headers, "headers"),
    map("metadatas", R.string.frp_form_field_metadata),
    map("annotations", R.string.frp_form_field_annotations)
)

/** 檢查表單涵蓋的欄位型別；不相容的 TOML 保留給文字模式修正。 */
fun canEditFrpForm(document: FrpConfigDocument): Boolean {
    // 以下欄位只用於型別檢查，不顯示標籤，因此帶入 0。
    val common = connectionFields + transportFields + tlsFields + oidcFields + listOf(
        choice("auth.method", 0, "token", "oidc"), list("auth.additionalScopes", 0),
        FrpField("auth.token", 0), FrpField("natHoleStunServer", 0), FrpField("dnsServer", 0),
        number("udpPacketSize", 0), map("metadatas", 0),
        number("transport.quic.keepalivePeriod", 0), number("transport.quic.maxIdleTimeout", 0),
        number("transport.quic.maxIncomingStreams", 0)
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
        Text(stringResource(R.string.frp_form_note), style = MaterialTheme.typography.bodySmall)
    }
    item(key = "frp/common/1") {
        FrpSection(
            stringResource(R.string.frp_form_section_basic),
            formState,
            "common/connection"
        ) { FrpFields(connectionFields, document::get, edit, enabled, "common", formState) }
    }
    item(key = "frp/common/2") {
        FrpSection(stringResource(R.string.frp_form_section_auth), formState, "common/auth") {
            FrpFields(
                listOf(choice("auth.method", R.string.frp_form_field_auth_method, "token", "oidc")),
                document::get,
                edit,
                enabled,
                "common",
                formState
            )
            if ((document.get("auth.method") as? String ?: "token") == "oidc") {
                FrpFields(oidcFields, document::get, edit, enabled, "common", formState)
            } else {
                FrpFields(
                    listOf(secret("auth.token", R.string.frp_form_field_auth_token)),
                    document::get,
                    edit,
                    enabled,
                    "common",
                    formState
                )
            }
            listOf(
                "HeartBeats" to R.string.frp_form_switch_auth_heartbeats,
                "NewWorkConns" to R.string.frp_form_switch_auth_new_work_conns
            ).forEach { (scope, label) ->
                val scopes = (document.get("auth.additionalScopes") as? List<*>)?.filterIsInstance<String>().orEmpty()
                FrpSwitch(label, scope in scopes, enabled) { checked ->
                    edit("auth.additionalScopes", if (checked) (scopes + scope).distinct() else scopes - scope)
                }
            }
        }
    }
    item(key = "frp/common/3") {
        FrpSection(stringResource(R.string.frp_form_section_transport), formState, "common/transport") {
            FrpFields(transportFields, document::get, edit, enabled, "common", formState)
            if (document.get("transport.protocol") == "quic") {
                FrpFields(
                    listOf(
                        number(
                            "transport.quic.keepalivePeriod",
                            R.string.frp_form_field_quic_keepalive,
                            R.string.frp_form_hint_quic_keepalive
                        ),
                        number(
                            "transport.quic.maxIdleTimeout",
                            R.string.frp_form_field_quic_idle_timeout,
                            R.string.frp_form_hint_quic_idle_timeout
                        ),
                        number(
                            "transport.quic.maxIncomingStreams",
                            R.string.frp_form_field_quic_streams,
                            R.string.frp_form_hint_quic_streams
                        )
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
        FrpSection(stringResource(R.string.frp_form_section_tls), formState, "common/tls") {
            FrpFields(tlsFields, document::get, edit, enabled, "common", formState)
        }
    }
    item(key = "frp/common/5") {
        FrpSection(stringResource(R.string.frp_form_section_other), formState, "common/other") {
            FrpFields(
                listOf(
                    FrpField(
                        "natHoleStunServer",
                        R.string.frp_form_field_stun_server,
                        hint = R.string.frp_form_hint_stun_server
                    ),
                    FrpField("dnsServer", R.string.frp_form_field_dns_server),
                    number(
                        "udpPacketSize",
                        R.string.frp_form_field_udp_packet_size,
                        R.string.frp_form_hint_udp_packet_size
                    ),
                    map("metadatas", R.string.frp_form_field_client_metadata)
                ),
                document::get,
                edit,
                enabled,
                "common",
                formState
            )
        }
    }
    listOf(
        "proxies" to R.string.frp_form_section_proxies,
        "visitors" to R.string.frp_form_section_visitors
    ).forEach { (kind, title) ->
        val rules = document.rules(kind)
        item(key = "frp/$kind/add") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
                val newType = formState.newRuleTypes[kind] ?: if (kind == "proxies") "tcp" else "stcp"
                FrpChoice(R.string.frp_form_new_rule_protocol, newType, if (kind == "proxies") proxyTypes else visitorTypes, enabled) {
                    formState.newRuleTypes[kind] = it
                }
                OutlinedButton(onClick = { onChange(document.addRule(kind, newType)) }, enabled = enabled && valid) {
                    Text(
                        stringResource(
                            if (kind == "proxies") R.string.frp_form_add_proxy else R.string.frp_form_add_visitor
                        )
                    )
                }
            }
        }
        itemsIndexed(
            rules,
            key = { index, _ -> "frp/$kind/${document.ruleId(kind, index)}" },
            contentType = { _, _ -> "frp-rule" }
        ) { index, rule ->
            val type = rule["type"] as? String ?: "tcp"
            val prefix = "$kind/${document.ruleId(kind, index)}"
            val ruleName = rule["name"]?.toString() ?: stringResource(R.string.frp_form_rule_unnamed)
            FrpSection("$ruleName · ${type.uppercase()}", formState, prefix) {
                val get: (String) -> Any? = { path -> readRule(rule, path) }
                val set: (String, Any?) -> Unit = { path, value -> onChange(document.updateRule(kind, index, path, value)) }
                Text(
                    stringResource(R.string.frp_form_rule_protocol_note, type.uppercase()),
                    style = MaterialTheme.typography.bodySmall
                )
                FrpFields(ruleFields(kind, type), get, set, enabled, prefix, formState)
                if (kind == "proxies") {
                    FrpSection(stringResource(R.string.frp_form_section_proxy_advanced), formState, "$prefix/transport") {
                        FrpFields(proxyTransportFields, get, set, enabled, prefix, formState)
                    }
                }
                TextButton(onClick = {
                    formState.removeRuleInputs(prefix)
                    onChange(document.removeRule(kind, index))
                }, enabled = enabled) { Text(stringResource(R.string.frp_form_delete_rule)) }
            }
        }
    }
    if (!valid) {
        item(key = "frp/invalid") {
            Text(stringResource(R.string.frp_form_fix_invalid_fields), color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun ruleFields(kind: String, type: String): List<FrpField> = buildList {
    add(FrpField("name", R.string.frp_form_field_rule_name))
    add(toggle("enabled", R.string.frp_form_field_rule_enabled, true))
    if (kind == "visitors") {
        add(FrpField("serverName", R.string.frp_form_field_server_name))
        add(FrpField("serverUser", R.string.frp_form_field_server_user))
        add(secret("secretKey", R.string.frp_form_field_secret_key))
        add(FrpField("bindAddr", R.string.frp_form_field_bind_addr, hint = R.string.frp_form_hint_bind_addr))
        add(number("bindPort", R.string.frp_form_field_bind_port, R.string.frp_form_hint_bind_port))
        add(toggle("transport.useEncryption", R.string.frp_form_field_use_encryption))
        add(toggle("transport.useCompression", R.string.frp_form_field_use_compression))
        if (type == "xtcp") {
            add(choice("protocol", R.string.frp_form_field_nat_protocol, "quic", "kcp"))
            add(toggle("keepTunnelOpen", R.string.frp_form_field_keep_tunnel_open))
            add(number("maxRetriesAnHour", R.string.frp_form_field_max_retries, R.string.frp_form_hint_max_retries))
            add(
                number(
                    "minRetryInterval",
                    R.string.frp_form_field_min_retry_interval,
                    R.string.frp_form_hint_min_retry_interval
                )
            )
            add(FrpField("fallbackTo", R.string.frp_form_field_fallback_to))
            add(
                number(
                    "fallbackTimeoutMs",
                    R.string.frp_form_field_fallback_timeout,
                    R.string.frp_form_hint_fallback_timeout
                )
            )
            add(toggle("natTraversal.disableAssistedAddrs", R.string.frp_form_field_disable_assisted_addrs))
        }
    } else {
        add(FrpField("localIP", R.string.frp_form_field_local_ip, hint = R.string.frp_form_hint_local_ip))
        add(number("localPort", R.string.frp_form_field_local_port))
        when (type) {
            "tcp", "udp" -> add(
                number("remotePort", R.string.frp_form_field_remote_port, R.string.frp_form_hint_remote_port)
            )
            "http", "https", "tcpmux" -> {
                add(list("customDomains", R.string.frp_form_field_custom_domains))
                add(FrpField("subdomain", R.string.frp_form_field_subdomain))
                if (type != "https") {
                    add(FrpField("httpUser", R.string.frp_form_field_http_user))
                    add(secret("httpPassword", R.string.frp_form_field_http_password))
                    add(FrpField("routeByHTTPUser", R.string.frp_form_field_route_by_http_user))
                }
                if (type == "http") {
                    add(list("locations", R.string.frp_form_field_locations))
                    add(FrpField("hostHeaderRewrite", R.string.frp_form_field_host_header_rewrite))
                    add(map("requestHeaders.set", R.string.frp_form_field_request_headers))
                    add(map("responseHeaders.set", R.string.frp_form_field_response_headers))
                }
                if (type == "tcpmux") add(choice("multiplexer", R.string.frp_form_field_multiplexer, "httpconnect"))
            }
            "stcp", "sudp", "xtcp" -> {
                add(secret("secretKey", R.string.frp_form_field_secret_key))
                add(list("allowUsers", R.string.frp_form_field_allow_users))
                if (type == "xtcp") {
                    add(toggle("natTraversal.disableAssistedAddrs", R.string.frp_form_field_disable_assisted_addrs))
                }
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
                        label = { Text(stringResource(field.label)) },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        isError = id in formState.errors,
                        supportingText = {
                            val hint = field.hint
                            if (id in formState.errors) {
                                Text(stringResource(R.string.frp_form_invalid_integer))
                            } else if (hint != null) {
                                Text(stringResource(hint))
                            }
                        },
                        singleLine = field.kind != "list",
                        visualTransformation = if (field.kind == "secret" && !revealed) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        trailingIcon = if (field.kind == "secret") {
                            {
                                TextButton(onClick = { revealed = !revealed }, enabled = enabled) {
                                    Text(
                                        stringResource(
                                            if (revealed) R.string.frp_form_hide else R.string.frp_form_reveal
                                        )
                                    )
                                }
                            }
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
private fun FrpSwitch(@StringRes label: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun FrpChoice(@StringRes label: Int, value: String, choices: List<String>, enabled: Boolean, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(if (value.isEmpty()) stringResource(R.string.frp_form_default_choice) else value)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(if (item.isEmpty()) stringResource(R.string.frp_form_disabled_choice) else item)
                    },
                    onClick = {
                        expanded = false
                        onChange(item)
                    }
                )
            }
        }
    }
}

@Composable
private fun FrpMap(
    @StringRes label: Int,
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
    @StringRes label: Int,
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
    @StringRes label: Int,
    entries: List<Pair<String, String>>,
    enabled: Boolean,
    prefix: String,
    state: FrpFormState,
    onChange: (List<Pair<String, String>>) -> Unit
) {
    Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
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
                label = { Text(stringResource(R.string.frp_form_pair_name)) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = id in state.errors,
                supportingText = { if (id in state.errors) Text(stringResource(R.string.frp_form_pair_name_invalid)) }
            )
            OutlinedTextField(value, { updated ->
                onChange(entries.toMutableList().also { it[index] = name to updated })
            }, label = {
                Text(
                    stringResource(R.string.frp_form_pair_value)
                )
            }, enabled = enabled, modifier = Modifier.fillMaxWidth(), singleLine = true)
            TextButton(onClick = {
                onChange(entries.filterIndexed { position, _ -> position != index })
            }, enabled = enabled && state.isValid) { Text(stringResource(R.string.frp_form_delete_entry, name)) }
        }
    }
    OutlinedButton(onClick = {
        var name = "key"
        var suffix = 1
        while (entries.any { it.first == name }) name = "key${suffix++}"
        onChange(entries + (name to ""))
    }, enabled = enabled && state.isValid) {
        Text(stringResource(R.string.frp_form_add_entry, stringResource(label)))
    }
}
