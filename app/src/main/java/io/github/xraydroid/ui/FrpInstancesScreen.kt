package io.github.xraydroid.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xraydroid.R
import io.github.xraydroid.runtime.FrpConnectionPhase
import io.github.xraydroid.runtime.FrpInstance
import io.github.xraydroid.runtime.FrpPhase
import io.github.xraydroid.runtime.FrpState
import io.github.xraydroid.runtime.FrpStore
import io.github.xraydroid.ui.components.AppPage
import io.github.xraydroid.ui.components.ButtonLabel
import io.github.xraydroid.ui.components.FloatingControlClearance
import io.github.xraydroid.ui.components.IconBadge
import io.github.xraydroid.ui.components.SegmentedColumn
import io.github.xraydroid.ui.components.SegmentedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FrpInstancesScreen(instances: List<FrpInstance>, loaded: Boolean, onBack: () -> Unit, onOpenInstance: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val openCreateDialog = {
        if (loaded && !working) {
            editingId = null
            name = ""
            failed = false
            showDialog = true
        }
    }
    AppPage(
        title = stringResource(R.string.frp_title),
        onBack = onBack,
        backLabel = stringResource(R.string.nav_back_settings),
        bottomClearance = FloatingControlClearance,
        floatingActionButton = {
            // 使用內容版本的多載：圖示＋文字版本會把文字排除在無障礙語意之外。
            ExtendedFloatingActionButton(onClick = openCreateDialog) {
                ButtonLabel(R.drawable.ic_add, stringResource(R.string.frp_instance_add), large = true)
            }
        }
    ) {
        item {
            Text(
                stringResource(if (loaded) R.string.frp_instances_note else R.string.frp_config_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        if (instances.isNotEmpty()) {
            item {
                SegmentedColumn {
                    instances.forEachIndexed { index, instance ->
                        key(instance.id) {
                            InstanceRow(index, instances.size, instance, !working, { onOpenInstance(instance.id) }) {
                                editingId = instance.id
                                name = instance.name
                                failed = false
                                showDialog = true
                            }
                        }
                    }
                }
            }
        }
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { if (!working) showDialog = false },
            title = { Text(stringResource(if (editingId == null) R.string.frp_instance_add else R.string.frp_instance_rename)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            failed = false
                        },
                        label = { Text(stringResource(R.string.frp_instance_name)) },
                        singleLine = true,
                        enabled = !working,
                        isError = failed,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (failed) Text(stringResource(R.string.frp_instance_save_failed), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !working && name.trim().isNotEmpty() && name.trim().length <= 100,
                    onClick = {
                        val id = editingId
                        val submittedName = name.trim()
                        working = true
                        scope.launch {
                            try {
                                val created = withContext(Dispatchers.IO) {
                                    if (id == null) FrpStore.createInstance(context, submittedName) else null
                                }
                                val saved = if (id == null) {
                                    created != null
                                } else {
                                    withContext(Dispatchers.IO) {
                                        FrpStore.renameInstance(context, id, submittedName)
                                    }
                                }
                                if (saved) {
                                    showDialog = false
                                    created?.let(onOpenInstance)
                                } else {
                                    failed = true
                                }
                            } finally {
                                working = false
                            }
                        }
                    }
                ) { Text(stringResource(if (editingId == null) R.string.frp_instance_create else R.string.frp_instance_save_name)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }, enabled = !working) { Text(stringResource(R.string.frp_instance_cancel)) }
            }
        )
    }
}

@Composable
private fun InstanceRow(index: Int, count: Int, instance: FrpInstance, renameEnabled: Boolean, onOpen: () -> Unit, onRename: () -> Unit) {
    val store = remember(instance.id) { FrpStore.forInstance(instance.id) }
    val state by store.state.collectAsState()
    val active = state.phase == FrpPhase.RUNNING && state.connection.phase == FrpConnectionPhase.CONNECTED
    val failed = state.phase == FrpPhase.ERROR
    SegmentedItem(
        index = index,
        count = count,
        headline = instance.name,
        supporting = stringResource(frpPhaseTitle(state)),
        onClick = onOpen,
        leading = {
            IconBadge(
                icon = if (failed) R.drawable.ic_error else R.drawable.ic_swap_horiz,
                container = when {
                    failed -> MaterialTheme.colorScheme.errorContainer
                    active -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                },
                content = when {
                    failed -> MaterialTheme.colorScheme.onErrorContainer
                    active -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        },
        trailing = {
            IconButton(onClick = onRename, enabled = renameEnabled) {
                Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.frp_instance_rename))
            }
        }
    )
}

/** frp 實例的狀態標題；運作中時改顯示與伺服器的連線階段。 */
@StringRes
internal fun frpPhaseTitle(state: FrpState): Int = when (state.phase) {
    FrpPhase.STOPPED -> R.string.frp_phase_stopped
    FrpPhase.VALIDATING -> R.string.frp_phase_validating
    FrpPhase.STARTING -> R.string.frp_phase_starting
    FrpPhase.RUNNING -> state.connection.phase.titleRes
    FrpPhase.WAITING_FOR_NETWORK -> R.string.frp_phase_waiting_network
    FrpPhase.STOPPING -> R.string.frp_phase_stopping
    FrpPhase.ERROR -> R.string.frp_phase_error
}
