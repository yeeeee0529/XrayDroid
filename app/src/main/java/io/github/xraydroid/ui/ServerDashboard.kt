package io.github.xraydroid.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.xraydroid.BuildConfig
import io.github.xraydroid.runtime.ServerPhase
import io.github.xraydroid.runtime.ServerState

@Composable
fun ServerDashboard(state: ServerState, onStart: () -> Unit, onStop: () -> Unit, onRestart: () -> Unit, onOpenPanel: () -> Unit) {
    var logsExpanded by rememberSaveable { mutableStateOf(false) }
    val running = state.phase == ServerPhase.RUNNING
    val busy = state.phase == ServerPhase.STARTING || state.phase == ServerPhase.STOPPING
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { insets ->
        Box(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp)
            ) {
                item {
                    Column(modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                        Text("XrayDroid", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            "你的 3x-ui，隨身運作",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                item {
                    StatusCard(
                        state = state,
                        onStart = onStart,
                        onStop = onStop,
                        onRestart = onRestart
                    )
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(
                            Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("管理面板", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "在瀏覽器中管理入站連線、使用者與流量。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "首次登入使用 3x-ui 預設帳號；請在面板設定中變更密碼。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            SelectionContainer {
                                Text(
                                    state.panelUrl,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                            }
                            Button(
                                onClick = onOpenPanel,
                                enabled = running && !busy,
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                shape = RoundedCornerShape(20.dp)
                            ) { Text("開啟管理面板") }
                            if (!running) {
                                Text("啟動服務後即可開啟。", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Text("核心版本", style = MaterialTheme.typography.titleMedium)
                            VersionRow("3x-ui", BuildConfig.XUI_VERSION)
                            VersionRow("Xray-core", BuildConfig.XRAY_VERSION)
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("服務日誌", style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { logsExpanded = !logsExpanded }) {
                            Text(if (logsExpanded) "收合日誌" else "展開日誌")
                        }
                    }
                    if (!logsExpanded) {
                        Text(
                            "啟動遇到問題時，展開日誌查看詳細資訊。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (state.logs.isEmpty()) {
                        Text(
                            "尚無日誌。啟動服務後，執行訊息會顯示在這裡。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (logsExpanded) {
                    itemsIndexed(state.logs.takeLast(80)) { _, line ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            SelectionContainer {
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                    if (state.logs.isNotEmpty()) {
                        item {
                            Text(
                                "顯示最近 ${minOf(state.logs.size, 80)} 筆日誌",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun StatusCard(state: ServerState, onStart: () -> Unit, onStop: () -> Unit, onRestart: () -> Unit) {
    val running = state.phase == ServerPhase.RUNNING
    val busy = state.phase == ServerPhase.STARTING || state.phase == ServerPhase.STOPPING
    val failed = state.phase == ServerPhase.ERROR
    val container by animateColorAsState(
        targetValue = when {
            failed -> MaterialTheme.colorScheme.errorContainer
            running -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        label = "serviceStatusColor"
    )
    val contentColor = when {
        failed -> MaterialTheme.colorScheme.onErrorContainer
        running -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Card(
        shape = RoundedCornerShape(36.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = contentColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.size(10.dp).background(contentColor, CircleShape))
                Text("本機服務", style = MaterialTheme.typography.labelLarge)
            }
            Text(
                text = when (state.phase) {
                    ServerPhase.STOPPED -> "準備就緒"
                    ServerPhase.STARTING -> "正在啟動"
                    ServerPhase.RUNNING -> "服務運作中"
                    ServerPhase.STOPPING -> "正在停止"
                    ServerPhase.ERROR -> "服務發生問題"
                },
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            Text(
                text = when (state.phase) {
                    ServerPhase.STOPPED -> "啟動 3x-ui 與 Xray，開始管理你的連線。"
                    ServerPhase.STARTING -> "正在準備核心與管理面板，請稍候。"
                    ServerPhase.RUNNING -> "3x-ui 已啟動。關閉此畫面後，服務仍會在背景運作。"
                    ServerPhase.STOPPING -> "正在關閉管理面板與核心，請稍候。"
                    ServerPhase.ERROR -> "請查看服務日誌，排除問題後重新啟動。"
                },
                style = MaterialTheme.typography.bodyLarge
            )
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = contentColor)
            }
            if (failed && state.message.isNotBlank()) {
                SelectionContainer {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
            if (running) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onRestart,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(20.dp)
                    ) { Text("重新啟動") }
                    Button(
                        onClick = onStop,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(20.dp)
                    ) { Text("停止服務") }
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text(
                        if (failed) "再次啟動服務" else "啟動服務",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionRow(name: String, version: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(
            version,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        )
    }
}
