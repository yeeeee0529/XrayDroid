package io.github.xraydroid.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.xraydroid.R
import io.github.xraydroid.runtime.FrpConfigDocument
import io.github.xraydroid.runtime.FrpConnectionPhase
import io.github.xraydroid.runtime.FrpNetworkStore
import io.github.xraydroid.runtime.FrpPhase
import io.github.xraydroid.runtime.FrpState
import io.github.xraydroid.runtime.FrpStore
import io.github.xraydroid.runtime.frpProxyStatusLabel
import io.github.xraydroid.ui.components.AppPage
import io.github.xraydroid.ui.components.ButtonLabel
import io.github.xraydroid.ui.components.FloatingControlClearance
import io.github.xraydroid.ui.components.IconBadge
import io.github.xraydroid.ui.components.SectionHeader
import io.github.xraydroid.ui.components.SegmentedColumn
import io.github.xraydroid.ui.components.SegmentedItem
import io.github.xraydroid.ui.components.StatusHero
import io.github.xraydroid.ui.components.StatusTone
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FrpScreen(
    state: FrpState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    instanceId: String = FrpStore.DEFAULT_INSTANCE_ID,
    instanceName: String? = null
) {
    val context = LocalContext.current
    val store = remember(instanceId) { FrpStore.forInstance(instanceId) }
    val networkStore = remember(instanceId) { FrpNetworkStore.forInstance(instanceId) }
    val networkState by networkStore.state.collectAsState()
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
            val saved = withContext(Dispatchers.IO) { store.saveConfig(context, text) }
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
                    val path = withContext(Dispatchers.IO) { store.importSupportFile(context, uri) }
                    feedback = path?.let { context.getString(R.string.frp_feedback_support_imported, it) }
                        ?: context.getString(R.string.frp_feedback_support_failed)
                } finally {
                    working = false
                }
            }
        }
    }
    val editable = initialized && !busy
    AppPage(
        title = instanceName ?: stringResource(R.string.frp_client_title),
        onBack = leave,
        backLabel = stringResource(R.string.frp_instances_back),
        bottomClearance = FloatingControlClearance,
        bottomOverlay = {
            if (feedback.isNotBlank()) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shadowElevation = 3.dp
                ) {
                    Text(
                        feedback,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics {
                            liveRegion = LiveRegionMode.Polite
                        }
                    )
                }
            }
            HorizontalFloatingToolbar(expanded = true, colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors()) {
                TextButton(
                    onClick = {
                        exportDraft { text, _ ->
                            val valid = withContext(Dispatchers.IO) { store.validateConfig(context, text) }
                            feedback = context.getString(if (valid) R.string.frp_feedback_valid else R.string.frp_feedback_invalid)
                        }
                    },
                    enabled = canSave,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                ) { ButtonLabel(R.drawable.ic_check, stringResource(R.string.frp_validate)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { saveThen() }, enabled = canSave) {
                    ButtonLabel(R.drawable.ic_save, stringResource(R.string.frp_save))
                }
            }
        }
    ) {
        item {
            val connected = state.phase == FrpPhase.RUNNING && state.connection.phase == FrpConnectionPhase.CONNECTED
            StatusHero(
                tone = when {
                    state.phase == FrpPhase.ERROR -> StatusTone.Error
                    connected -> StatusTone.Active
                    else -> StatusTone.Idle
                },
                busy = busy,
                icon = when {
                    state.phase == FrpPhase.ERROR -> R.drawable.ic_error
                    state.phase == FrpPhase.WAITING_FOR_NETWORK -> R.drawable.ic_hourglass
                    connected -> R.drawable.ic_check
                    else -> R.drawable.ic_swap_horiz
                },
                label = stringResource(R.string.frp_title),
                title = stringResource(frpPhaseTitle(state))
            ) {
                if (state.phase == FrpPhase.RUNNING) {
                    Text(state.connection.detail.resolve(), style = MaterialTheme.typography.bodyLarge)
                    if (state.connection.attempts > 0 && state.connection.phase != FrpConnectionPhase.CONNECTED) {
                        Text(
                            pluralStringResource(
                                R.plurals.frp_connection_attempts,
                                state.connection.attempts,
                                state.connection.attempts
                            ),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    if (state.connection.phase == FrpConnectionPhase.CONNECTED) {
                        val running = state.proxies.count { it.status == "running" }
                        Text(
                            if (state.proxies.isEmpty()) {
                                stringResource(R.string.frp_proxies_empty)
                            } else {
                                stringResource(R.string.frp_proxies_summary, running, state.proxies.size)
                            },
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                state.message?.let { Text(it.resolve(), style = MaterialTheme.typography.bodyMedium) }
                if (state.phase in setOf(FrpPhase.RUNNING, FrpPhase.STARTING, FrpPhase.WAITING_FOR_NETWORK)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val contentColor = LocalContentColor.current
                        OutlinedButton(
                            onClick = { saveThen(onRestart) },
                            enabled = canSave,
                            shapes = ButtonDefaults.shapes(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
                            border = BorderStroke(1.dp, contentColor.copy(alpha = if (canSave) 1f else 0.12f)),
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                        ) { ButtonLabel(R.drawable.ic_refresh, stringResource(R.string.frp_restart)) }
                        Button(
                            onClick = onStop,
                            enabled = !working,
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp)
                        ) { ButtonLabel(R.drawable.ic_stop, stringResource(R.string.frp_stop)) }
                    }
                } else {
                    Button(
                        onClick = { saveThen(onStart) },
                        enabled = canSave,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    ) { ButtonLabel(R.drawable.ic_play_arrow, stringResource(R.string.frp_start), large = true) }
                }
            }
        }
        item {
            NetworkCard(
                state = networkState,
                appliedNetworkLabel = state.outboundNetworkLabel,
                enabled = !busy && state.phase != FrpPhase.STOPPING,
                onSelect = networkStore::select,
                onSelectInterface = networkStore::selectInterface,
                onRefresh = networkStore::refreshInterfaces,
                titleRes = R.string.frp_network_title,
                descriptionRes = R.string.frp_network_description
            )
        }
        if (state.proxies.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.frp_proxies_title)) }
            items(state.proxies) { proxy ->
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceBright) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${proxy.name} · ${proxy.type}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(frpProxyStatusLabel(proxy.status)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (proxy.remoteAddress.isNotBlank()) {
                            Text(
                                proxy.remoteAddress,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(stringResource(R.string.frp_config_mode_title))
                // 相連的切換按鈕；已選取的模式再次點按不做任何事。
                Row(
                    Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
                ) {
                    ToggleButton(
                        checked = formMode,
                        onCheckedChange = { if (!formMode) switchToForm() },
                        enabled = editable,
                        shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { role = Role.RadioButton }
                    ) { Text(stringResource(R.string.frp_config_mode_form)) }
                    ToggleButton(
                        checked = !formMode,
                        onCheckedChange = {
                            if (formMode) {
                                exportDraft { text, _ ->
                                    draft = text
                                    formMode = false
                                    formState.clear()
                                }
                            }
                        },
                        enabled = editable && (!formMode || formValid),
                        shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { role = Role.RadioButton }
                    ) { Text(stringResource(R.string.frp_config_mode_toml)) }
                }
                Text(
                    stringResource(R.string.frp_config_mode_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
        if (!initialized) {
            item(key = "frp/loading") {
                Text(
                    stringResource(R.string.frp_config_loading),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        } else if (formMode) {
            document?.let { current ->
                frpConfigFormItems(
                    document = current,
                    enabled = editable,
                    onChange = {
                        document = it
                        feedback = ""
                    },
                    formState = formState
                )
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(stringResource(R.string.frp_config_toml_title))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            feedback = ""
                        },
                        enabled = editable,
                        label = { Text(stringResource(R.string.frp_config_toml_label)) },
                        minLines = 12,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }
        }
        if (dirty) {
            item {
                Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!formValid) {
                        Text(stringResource(R.string.frp_form_invalid_block), color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        stringResource(R.string.frp_draft_unsaved),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            SegmentedColumn(Modifier.padding(top = 12.dp)) {
                SegmentedItem(
                    index = 0,
                    count = 3,
                    headline = stringResource(R.string.frp_import_toml),
                    onClick = { importConfig.launch(arrayOf("*/*")) },
                    enabled = editable,
                    leading = { IconBadge(R.drawable.ic_upload) }
                )
                SegmentedItem(
                    index = 1,
                    count = 3,
                    headline = stringResource(R.string.frp_import_support),
                    supporting = stringResource(R.string.frp_import_support_note),
                    onClick = { importSupport.launch(arrayOf("*/*")) },
                    enabled = editable,
                    leading = { IconBadge(R.drawable.ic_upload) }
                )
                SegmentedItem(
                    index = 2,
                    count = 3,
                    headline = stringResource(R.string.frp_unsafe_toggle),
                    supporting = stringResource(R.string.frp_unsafe_note),
                    trailing = {
                        Switch(
                            checked = state.allowUnsafeTokenCommand,
                            onCheckedChange = { enabled ->
                                working = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { store.setAllowUnsafeTokenCommand(context, enabled) }
                                    } finally {
                                        working = false
                                    }
                                }
                            },
                            enabled = editable && state.phase !in setOf(FrpPhase.RUNNING, FrpPhase.WAITING_FOR_NETWORK)
                        )
                    }
                )
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
