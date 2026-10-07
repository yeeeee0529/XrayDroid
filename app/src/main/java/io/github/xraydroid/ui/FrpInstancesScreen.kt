package io.github.xraydroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xraydroid.R
import io.github.xraydroid.runtime.FrpInstance
import io.github.xraydroid.runtime.FrpPhase
import io.github.xraydroid.runtime.FrpStore
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
    SettingsPage(R.string.frp_title, R.string.nav_back_settings, onBack) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.frp_instances_note))
                Button(
                    onClick = {
                        editingId = null
                        name = ""
                        failed = false
                        showDialog = true
                    },
                    enabled = loaded && !working,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.frp_instance_add)) }
                if (!loaded) Text(stringResource(R.string.frp_config_loading))
            }
        }
        items(instances, key = { it.id }) { instance ->
            val store = remember(instance.id) { FrpStore.forInstance(instance.id) }
            val state by store.state.collectAsState()
            Card(onClick = { onOpenInstance(instance.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(instance.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(
                            when (state.phase) {
                                FrpPhase.STOPPED -> R.string.frp_phase_stopped
                                FrpPhase.VALIDATING -> R.string.frp_phase_validating
                                FrpPhase.STARTING -> R.string.frp_phase_starting
                                FrpPhase.RUNNING -> state.connection.phase.titleRes
                                FrpPhase.WAITING_FOR_NETWORK -> R.string.frp_phase_waiting_network
                                FrpPhase.STOPPING -> R.string.frp_phase_stopping
                                FrpPhase.ERROR -> R.string.frp_phase_error
                            }
                        )
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            editingId = instance.id
                            name = instance.name
                            failed = false
                            showDialog = true
                        }, enabled = !working) { Text(stringResource(R.string.frp_instance_rename)) }
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
