package io.github.xraydroid.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.xraydroid.runtime.FrpPhase
import io.github.xraydroid.runtime.FrpState
import io.github.xraydroid.runtime.FrpStore
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FrpScreen(state: FrpState, onBack: () -> Unit, onStart: () -> Unit, onStop: () -> Unit, onRestart: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 設定與權杖只留在記憶體，不寫入 Activity 儲存狀態。
    var draft by remember { mutableStateOf(state.config) }
    var initialized by remember { mutableStateOf(state.loaded) }
    var working by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf(false) }
    var pendingTemplate by remember { mutableStateOf<String?>(null) }
    var showStarter by remember { mutableStateOf(false) }
    val busy = working || state.phase in setOf(FrpPhase.VALIDATING, FrpPhase.STARTING, FrpPhase.STOPPING)
    val dirty = draft != state.config
    LaunchedEffect(state.loaded) {
        if (state.loaded && !initialized) {
            draft = state.config
            initialized = true
        }
    }
    val leave: () -> Unit = { if (dirty) confirmLeave = true else onBack() }
    BackHandler { leave() }
    fun saveThen(action: (() -> Unit)? = null) {
        working = true
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { FrpStore.saveConfig(context, draft) }
                feedback = if (saved) "設定已儲存；執行中的用戶端需重新啟動套用。" else "設定未儲存，請確認 TOML 格式與設定值。"
                if (saved) action?.invoke()
            } finally {
                working = false
            }
        }
    }
    val importConfig = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            working = true
            scope.launch {
                try {
                    val imported = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    check(output.size() + count <= 1024 * 1024) { "Configuration exceeds size limit" }
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray().decodeToString(throwOnInvalidSequence = true)
                            }
                        }.getOrNull()
                    }
                    if (imported == null) {
                        feedback = "無法匯入；請選擇 UTF-8 編碼、大小不超過 1 MiB 的 TOML 設定檔。"
                    } else {
                        pendingTemplate = imported
                    }
                } finally {
                    working = false
                }
            }
        }
    }
    val importSupport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            working = true
            scope.launch {
                try {
                    val path = withContext(Dispatchers.IO) { FrpStore.importSupportFile(context, uri) }
                    feedback = path?.let { "檔案已匯入；在 TOML 中使用路徑：$it" } ?: "檔案匯入失敗，請確認檔案大小與名稱。"
                } finally {
                    working = false
                }
            }
        }
    }
    SettingsPage("frp", "返回設定", leave) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("frpc 用戶端", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when (state.phase) {
                            FrpPhase.STOPPED -> "已停止"
                            FrpPhase.VALIDATING -> "正在驗證設定"
                            FrpPhase.STARTING -> "正在啟動"
                            FrpPhase.RUNNING -> "用戶端運作中"
                            FrpPhase.STOPPING -> "正在停止"
                            FrpPhase.ERROR -> "用戶端發生問題"
                        },
                        style = MaterialTheme.typography.headlineSmall
                    )
                    if (state.message.isNotBlank()) Text(state.message)
                    Text("獨立於 3x-ui 與 Xray；使用 Android 系統預設網路。")
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.phase == FrpPhase.RUNNING || state.phase == FrpPhase.STARTING) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { saveThen(onRestart) }, enabled = !busy && initialized) { Text("重新啟動 frpc") }
                            Button(onClick = onStop, enabled = !working) { Text("停止 frpc") }
                        }
                    } else {
                        Button(onClick = { saveThen(onStart) }, enabled = !busy && initialized) { Text("啟動 frpc") }
                    }
                }
            }
        }
        if (state.proxies.isNotEmpty()) {
            item { Text("代理狀態", style = MaterialTheme.typography.titleLarge) }
            items(state.proxies) { proxy ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${proxy.name} · ${proxy.type}", style = MaterialTheme.typography.titleMedium)
                        Text(proxy.status)
                        if (proxy.remoteAddress.isNotBlank()) Text(proxy.remoteAddress)
                    }
                }
            }
        }
        item {
            Text("完整 TOML 設定", style = MaterialTheme.typography.titleLarge)
            Text("支援 frpc 的代理、訪客、驗證、傳輸與進階設定；驗證成功才會儲存及啟動。")
            Text(
                "App 會管理 webServer 本機狀態端點、丟棄原始日誌，並覆寫 loginFailExit = false 以持續重連；這些執行時設定不會修改已儲存的 TOML。",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    feedback = ""
                },
                enabled = initialized && !busy,
                label = { Text("frpc.toml") },
                minLines = 12,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        working = true
                        scope.launch {
                            try {
                                val valid = withContext(Dispatchers.IO) { FrpStore.validateConfig(context, draft) }
                                feedback = if (valid) "設定驗證通過；尚未儲存。" else "設定驗證失敗，請確認 TOML 格式與設定值。"
                            } finally {
                                working = false
                            }
                        }
                    },
                    enabled = !busy && initialized
                ) { Text("驗證設定") }
                Button(onClick = { saveThen() }, enabled = !busy && initialized) { Text("儲存設定") }
            }
            if (feedback.isNotBlank()) Text(feedback, modifier = Modifier.padding(top = 12.dp))
            if (dirty) Text("草稿尚未儲存。", style = MaterialTheme.typography.labelMedium)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { importConfig.launch(arrayOf("*/*")) }, enabled = !busy && initialized) { Text("匯入 TOML") }
                OutlinedButton(onClick = { importSupport.launch(arrayOf("*/*")) }, enabled = !busy && initialized) { Text("匯入憑證或檔案") }
            }
            Text("憑證與外掛所需檔案會複製至 App 私有目錄。請在設定中填入匯入後的相對路徑。", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("允許外部權杖指令", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Switch(
                            checked = state.allowUnsafeTokenCommand,
                            onCheckedChange = { enabled ->
                                working = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { FrpStore.setAllowUnsafeTokenCommand(context, enabled) }
                                    } finally {
                                        working = false
                                    }
                                }
                            },
                            enabled = initialized && !busy && state.phase != FrpPhase.RUNNING
                        )
                    }
                    Text(
                        "預設關閉。啟用後允許 tokenSource 執行外部指令取得權杖；指令會使用此 App 的權限，執行檔路徑仍須符合 Android 限制。請先停止 frpc 再變更。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        item {
            TextButton(onClick = { showStarter = !showStarter }, enabled = !busy && initialized) {
                Text(if (showStarter) "收合基本範本" else "建立基本範本")
            }
            if (showStarter) FrpStarter(enabled = !busy) { pendingTemplate = it }
        }
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("捨棄未儲存的草稿？") },
            text = { Text("已儲存的 frpc 設定會保留。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onBack()
                }) { Text("捨棄並返回") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("繼續編輯") } }
        )
    }
    pendingTemplate?.let { replacement ->
        AlertDialog(
            onDismissRequest = { pendingTemplate = null },
            title = { Text("取代目前草稿？") },
            text = { Text("這會取代完整 TOML 草稿，包括進階設定；確認後仍需驗證並儲存。") },
            confirmButton = {
                TextButton(onClick = {
                    draft = replacement
                    pendingTemplate = null
                    feedback = ""
                }) { Text("取代草稿") }
            },
            dismissButton = { TextButton(onClick = { pendingTemplate = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun FrpStarter(enabled: Boolean, onGenerate: (String) -> Unit) {
    var server by remember { mutableStateOf("") }
    var serverPort by remember { mutableStateOf("7000") }
    var token by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("tcp-forward") }
    var localAddress by remember { mutableStateOf("127.0.0.1") }
    var localPort by remember { mutableStateOf("2053") }
    var remotePort by remember { mutableStateOf("12053") }
    val portKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("此表單只產生一份連線與 TCP 轉發範本。其他協定與訪客請編輯完整 TOML。")
        OutlinedTextField(server, { server = it }, label = { Text("frps 主機") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(serverPort, {
            serverPort = it
        }, label = { Text("frps 連接埠") }, enabled = enabled, keyboardOptions = portKeyboard, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, {
            token = it
        }, label = {
            Text("驗證權杖（可留空）")
        }, enabled = enabled, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it }, label = { Text("代理名稱") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            localAddress,
            { localAddress = it },
            label = { Text("本機位址") },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(localPort, {
            localPort = it
        }, label = { Text("本機連接埠") }, enabled = enabled, keyboardOptions = portKeyboard, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(remotePort, {
            remotePort = it
        }, label = { Text("遠端連接埠") }, enabled = enabled, keyboardOptions = portKeyboard, modifier = Modifier.fillMaxWidth())
        val valid = server.isNotBlank() && name.isNotBlank() && localAddress.isNotBlank() &&
            listOf(serverPort, localPort, remotePort).all { value -> value.toIntOrNull()?.let { it in 1..65535 } == true }
        Button(
            onClick = {
                onGenerate(
                    "serverAddr = ${tomlString(server)}\nserverPort = ${serverPort.toInt()}\n" +
                        (if (token.isNotEmpty()) "auth.method = \"token\"\nauth.token = ${tomlString(token)}\n" else "") +
                        "\n[[proxies]]\nname = ${tomlString(name)}\ntype = \"tcp\"\n" +
                        "localIP = ${tomlString(localAddress)}\nlocalPort = ${localPort.toInt()}\nremotePort = ${remotePort.toInt()}\n"
                )
            },
            enabled = enabled && valid
        ) { Text("產生範本") }
    }
}

private fun tomlString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        append(
            when (character) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> if (character.code < 32 || character.code == 127) "\\u%04x".format(character.code) else character.toString()
            }
        )
    }
    append('"')
}
