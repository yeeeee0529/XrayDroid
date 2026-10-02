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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.xraydroid.R
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
            feedback = context.getString(
                if (saved) R.string.frp_feedback_saved_running else R.string.frp_feedback_save_failed
            )
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
                    feedback = context.getString(R.string.frp_feedback_form_switch_failed)
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
                        feedback = context.getString(R.string.frp_feedback_import_failed)
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
                    feedback = path?.let { context.getString(R.string.frp_feedback_support_imported, it) }
                        ?: context.getString(R.string.frp_feedback_support_failed)
                } finally {
                    working = false
                }
            }
        }
    }
    SettingsPage(R.string.frp_title, R.string.nav_back_settings, leave) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.frp_client_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        when (state.phase) {
                            FrpPhase.STOPPED -> stringResource(R.string.frp_phase_stopped)
                            FrpPhase.VALIDATING -> stringResource(R.string.frp_phase_validating)
                            FrpPhase.STARTING -> stringResource(R.string.frp_phase_starting)
                            FrpPhase.RUNNING -> stringResource(state.connection.phase.titleRes)
                            FrpPhase.STOPPING -> stringResource(R.string.frp_phase_stopping)
                            FrpPhase.ERROR -> stringResource(R.string.frp_phase_error)
                        },
                        style = MaterialTheme.typography.headlineSmall
                    )
                    if (state.phase == FrpPhase.RUNNING) {
                        Text(state.connection.detail.resolve())
                        if (state.connection.attempts > 0 && state.connection.phase != FrpConnectionPhase.CONNECTED) {
                            Text(
                                stringResource(R.string.frp_connection_attempts, state.connection.attempts),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        if (state.connection.phase == FrpConnectionPhase.CONNECTED) {
                            val running = state.proxies.count { it.status == "running" }
                            Text(
                                if (state.proxies.isEmpty()) {
                                    stringResource(R.string.frp_proxies_empty)
                                } else {
                                    stringResource(R.string.frp_proxies_summary, running, state.proxies.size)
                                }
                            )
                        }
                    }
                    state.message?.let { Text(it.resolve()) }
                    Text(stringResource(R.string.frp_client_note))
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.phase == FrpPhase.RUNNING || state.phase == FrpPhase.STARTING) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = { saveThen(onRestart) },
                                enabled = canSave
                            ) { Text(stringResource(R.string.frp_restart)) }
                            Button(onClick = onStop, enabled = !working) { Text(stringResource(R.string.frp_stop)) }
                        }
                    } else {
                        Button(onClick = { saveThen(onStart) }, enabled = canSave) { Text(stringResource(R.string.frp_start)) }
                    }
                }
            }
        }
        if (state.proxies.isNotEmpty()) {
            item { Text(stringResource(R.string.frp_proxies_title), style = MaterialTheme.typography.titleLarge) }
            items(state.proxies) { proxy ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${proxy.name} · ${proxy.type}", style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(frpProxyStatusLabel(proxy.status)))
                        if (proxy.remoteAddress.isNotBlank()) Text(proxy.remoteAddress)
                    }
                }
            }
        }
        item {
            Text(stringResource(R.string.frp_config_mode_title), style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (formMode) {
                    Button(onClick = {}, enabled = initialized && !busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.frp_config_mode_form))
                    }
                } else {
                    OutlinedButton(
                        onClick = ::switchToForm,
                        enabled = initialized && !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.frp_config_mode_form)) }
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
                    ) { Text(stringResource(R.string.frp_config_mode_toml)) }
                } else {
                    Button(onClick = {}, enabled = initialized && !busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.frp_config_mode_toml))
                    }
                }
            }
            Text(stringResource(R.string.frp_config_mode_note), style = MaterialTheme.typography.bodySmall)
        }
        if (!initialized) {
            item(key = "frp/loading") { Text(stringResource(R.string.frp_config_loading)) }
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
                Text(stringResource(R.string.frp_config_toml_title), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        feedback = ""
                    },
                    enabled = initialized && !busy,
                    label = { Text(stringResource(R.string.frp_config_toml_label)) },
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
                            feedback = context.getString(
                                if (valid) R.string.frp_feedback_valid else R.string.frp_feedback_invalid
                            )
                        }
                    },
                    enabled = canSave
                ) { Text(stringResource(R.string.frp_validate)) }
                Button(onClick = { saveThen() }, enabled = canSave) { Text(stringResource(R.string.frp_save)) }
            }
            if (feedback.isNotBlank()) Text(feedback, modifier = Modifier.padding(top = 12.dp))
            if (!formValid) {
                Text(stringResource(R.string.frp_form_invalid_block), color = MaterialTheme.colorScheme.error)
            }
            if (dirty) Text(stringResource(R.string.frp_draft_unsaved), style = MaterialTheme.typography.labelMedium)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { importConfig.launch(arrayOf("*/*")) }, enabled = !busy && initialized) {
                    Text(stringResource(R.string.frp_import_toml))
                }
                OutlinedButton(onClick = { importSupport.launch(arrayOf("*/*")) }, enabled = !busy && initialized) {
                    Text(stringResource(R.string.frp_import_support))
                }
            }
            Text(stringResource(R.string.frp_import_support_note), style = MaterialTheme.typography.bodySmall)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.frp_unsafe_toggle),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium
                        )
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
                        stringResource(R.string.frp_unsafe_note),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.frp_discard_dialog_title)) },
            text = { Text(stringResource(R.string.frp_discard_dialog_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onBack()
                }) { Text(stringResource(R.string.frp_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.frp_discard_cancel)) }
            }
        )
    }
    pendingTemplate?.let { replacement ->
        AlertDialog(
            onDismissRequest = { pendingTemplate = null },
            title = { Text(stringResource(R.string.frp_replace_dialog_title)) },
            text = { Text(stringResource(R.string.frp_replace_dialog_text)) },
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
                }) { Text(stringResource(R.string.frp_replace_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingTemplate = null }) { Text(stringResource(R.string.frp_replace_cancel)) }
            }
        )
    }
}

private fun parseFormDocument(text: String): FrpConfigDocument? = runCatching {
    require(text.toByteArray(Charsets.UTF_8).size <= FrpStore.MAX_CONFIG_BYTES) { "Configuration exceeds size limit" }
    FrpConfigDocument.parse(text).takeIf(::canEditFrpForm)
}.getOrNull()
