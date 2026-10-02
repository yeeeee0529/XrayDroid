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
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.xraydroid.runtime.FrpConfigDocument
import io.github.xraydroid.runtime.FrpConnectionPhase
import io.github.xraydroid.runtime.FrpPhase
import io.github.xraydroid.runtime.FrpState
import io.github.xraydroid.runtime.FrpStore
import io.github.xraydroid.runtime.frpProxyStatusLabel
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
    var initialized by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    var confirmLeave by remember { mutableStateOf(false) }
    var pendingTemplate by remember { mutableStateOf<String?>(null) }
    var document by remember { mutableStateOf<FrpConfigDocument?>(null, referentialEqualityPolicy()) }
    var savedDocument by remember { mutableStateOf<FrpConfigDocument?>(null, referentialEqualityPolicy()) }
    var savedText by remember { mutableStateOf(state.config) }
    var formMode by remember { mutableStateOf(false) }
    val formState = remember { FrpFormState() }
    val formValid = formState.isValid
    val busy = working || !initialized || state.phase in setOf(FrpPhase.VALIDATING, FrpPhase.STARTING, FrpPhase.STOPPING)
    // 表單只比較草稿版本；避免每次輸入為了判斷未存變更而產生整份 TOML。
    val edited = if (formMode) document !== savedDocument || savedText != state.config else draft != state.config
    val dirty = initialized && edited || !formValid
    val canSave = !busy && formValid
    LaunchedEffect(state.loaded, state.config) {
        if (state.loaded && !initialized) {
            val text = state.config
            val parsed = withContext(Dispatchers.Default) { parseFormDocument(text) }
            draft = text
            savedText = text
            document = parsed
            savedDocument = parsed
            formMode = parsed != null
            initialized = true
        }
    }
    val leave: () -> Unit = { if (dirty) confirmLeave = true else onBack() }
    // 有未存變更時返回先彈確認；乾淨時交給 MainActivity 的 predictive back 過場。
    BackHandler(enabled = dirty) { leave() }
    fun exportDraft(consume: suspend (String, FrpConfigDocument?) -> Unit) {
        if (busy) return
        val snapshot = if (formMode) document else null
        val text = draft
        working = true
        scope.launch {
            try {
                val exported = withContext(Dispatchers.Default) { snapshot?.source ?: text }
                consume(exported, snapshot)
            } finally {
                working = false
            }
        }
    }
    fun saveThen(action: (() -> Unit)? = null) {
        exportDraft { text, snapshot ->
            val saved = withContext(Dispatchers.IO) { FrpStore.saveConfig(context, text) }
            feedback = if (saved) "設定已儲存；執行中的用戶端需重新啟動套用。" else "設定未儲存，請確認 TOML 格式與設定值。"
            if (saved) {
                draft = text
                savedText = text
                savedDocument = snapshot
                action?.invoke()
            }
        }
    }
    fun switchToForm() {
        if (busy) return
        val text = draft
        working = true
        scope.launch {
            try {
                val parsed = withContext(Dispatchers.Default) { parseFormDocument(text) }
                if (parsed == null) {
                    feedback = "無法切換至表單，請檢查 TOML 語法與欄位型別；原有草稿已保留。"
                } else {
                    document = parsed
                    if (text == state.config) {
                        savedDocument = parsed
                        savedText = text
                    }
                    formMode = true
                    formState.clear()
                    feedback = ""
                }
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
                            FrpPhase.RUNNING -> state.connection.phase.title
                            FrpPhase.STOPPING -> "正在停止"
                            FrpPhase.ERROR -> "用戶端發生問題"
                        },
                        style = MaterialTheme.typography.headlineSmall
                    )
                    if (state.phase == FrpPhase.RUNNING) {
                        Text(state.connection.detail)
                        if (state.connection.attempts > 0 && state.connection.phase != FrpConnectionPhase.CONNECTED) {
                            Text("已嘗試連線 ${state.connection.attempts} 次", style = MaterialTheme.typography.labelMedium)
                        }
                        if (state.connection.phase == FrpConnectionPhase.CONNECTED) {
                            val running = state.proxies.count { it.status == "running" }
                            Text(
                                if (state.proxies.isEmpty()) {
                                    "目前沒有代理狀態；僅使用訪客規則時不會列出代理。"
                                } else {
                                    "代理已啟用 $running / ${state.proxies.size}；代理啟用不代表本機目標服務可用。"
                                }
                            )
                        }
                    }
                    if (state.message.isNotBlank()) Text(state.message)
                    Text("獨立於 3x-ui 與 Xray；使用 Android 系統預設網路。")
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.phase == FrpPhase.RUNNING || state.phase == FrpPhase.STARTING) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { saveThen(onRestart) }, enabled = canSave) { Text("重新啟動 frpc") }
                            Button(onClick = onStop, enabled = !working) { Text("停止 frpc") }
                        }
                    } else {
                        Button(onClick = { saveThen(onStart) }, enabled = canSave) { Text("啟動 frpc") }
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
                        Text(frpProxyStatusLabel(proxy.status))
                        if (proxy.remoteAddress.isNotBlank()) Text(proxy.remoteAddress)
                    }
                }
            }
        }
        item {
            Text("配置模式", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (formMode) {
                    Button(onClick = {}, enabled = initialized && !busy, modifier = Modifier.weight(1f)) { Text("表單配置") }
                } else {
                    OutlinedButton(
                        onClick = ::switchToForm,
                        enabled = initialized && !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("表單配置") }
                }
                if (formMode) {
                    OutlinedButton(
                        onClick = {
                            exportDraft { text, _ ->
                                draft = text
                                formMode = false
                                formState.clear()
                            }
                        },
                        enabled = initialized && !busy && formValid,
                        modifier = Modifier.weight(1f)
                    ) { Text("TOML 配置") }
                } else {
                    Button(onClick = {}, enabled = initialized && !busy, modifier = Modifier.weight(1f)) { Text("TOML 配置") }
                }
            }
            Text("兩種模式共用同一份草稿；表單修改會重新排版 TOML 並移除註解，其他設定值會保留。", style = MaterialTheme.typography.bodySmall)
        }
        if (!initialized) {
            item(key = "frp/loading") { Text("正在載入設定") }
        } else if (formMode) {
            document?.let { current ->
                frpConfigFormItems(
                    document = current,
                    enabled = initialized && !busy,
                    onChange = {
                        document = it
                        feedback = ""
                    },
                    formState = formState
                )
            }
        } else {
            item {
                Text("完整 TOML 設定", style = MaterialTheme.typography.titleLarge)
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
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        exportDraft { text, _ ->
                            val valid = withContext(Dispatchers.IO) { FrpStore.validateConfig(context, text) }
                            feedback = if (valid) "設定驗證通過；尚未儲存。" else "設定驗證失敗，請確認 TOML 格式與設定值。"
                        }
                    },
                    enabled = canSave
                ) { Text("驗證設定") }
                Button(onClick = { saveThen() }, enabled = canSave) { Text("儲存設定") }
            }
            if (feedback.isNotBlank()) Text(feedback, modifier = Modifier.padding(top = 12.dp))
            if (!formValid) Text("請先修正表單中的欄位，再切換模式、驗證或儲存。", color = MaterialTheme.colorScheme.error)
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
                    if (!working) {
                        working = true
                        val preferForm = formMode
                        scope.launch {
                            try {
                                val parsed = withContext(Dispatchers.Default) { parseFormDocument(replacement) }
                                draft = replacement
                                document = parsed
                                if (replacement == state.config) {
                                    savedDocument = parsed
                                    savedText = replacement
                                }
                                formMode = preferForm && parsed != null
                                formState.clear()
                                pendingTemplate = null
                                feedback = ""
                            } finally {
                                working = false
                            }
                        }
                    }
                }) { Text("取代草稿") }
            },
            dismissButton = { TextButton(onClick = { pendingTemplate = null }) { Text("取消") } }
        )
    }
}

private fun parseFormDocument(text: String): FrpConfigDocument? = runCatching {
    require(text.toByteArray(Charsets.UTF_8).size <= FrpStore.MAX_CONFIG_BYTES) { "Configuration exceeds size limit" }
    FrpConfigDocument.parse(text).takeIf(::canEditFrpForm)
}.getOrNull()
