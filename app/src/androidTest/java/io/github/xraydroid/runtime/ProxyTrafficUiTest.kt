package io.github.xraydroid.runtime

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import io.github.xraydroid.ui.ServerDashboard
import io.github.xraydroid.ui.theme.XrayDroidTheme
import org.junit.Rule
import org.junit.Test

class ProxyTrafficUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dashboardShowsDirectionUnitsAndClearsUnavailableValues() {
        val state =
            mutableStateOf(ServerState(phase = ServerPhase.RUNNING, traffic = ProxyTraffic(TrafficStatus.READY, 1_500_000.0, 500_000.0)))
        compose.setContent {
            XrayDroidTheme {
                ServerDashboard(state.value, {}, {}, {}, {}, {})
            }
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("上傳"))
        compose.onNodeWithText("上傳").assertIsDisplayed()
        compose.onNodeWithText("下載").assertIsDisplayed()
        compose.onNodeWithText("1.5 MB/s").assertIsDisplayed()
        compose.onNodeWithText("500.0 KB/s").assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(traffic = ProxyTraffic(TrafficStatus.READY, 0.0, 1_200_000_000.0))
        }
        compose.onNodeWithText("0 B/s").assertIsDisplayed()
        compose.onNodeWithText("1.2 GB/s").assertIsDisplayed()
        for ((status, label) in listOf(TrafficStatus.WAITING to "正在取得速度…", TrafficStatus.UNAVAILABLE to "暫時無法取得速度。")) {
            compose.runOnIdle { state.value = state.value.copy(traffic = ProxyTraffic(status)) }
            compose.onNodeWithText(label).assertIsDisplayed()
            compose.onAllNodesWithText("—").assertCountEquals(2)
        }
        compose.runOnIdle { state.value = state.value.copy(phase = ServerPhase.STOPPED) }
        compose.onNodeWithText("啟動服務後顯示速度。").assertIsDisplayed()
        compose.onAllNodesWithText("—").assertCountEquals(2)
    }
}
