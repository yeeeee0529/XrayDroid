package io.github.xraydroid.runtime

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xraydroid.ui.FrpScreen
import org.junit.Rule
import org.junit.Test

class FrpConnectionScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun topCardShowsLoginFailureRecoveryAndLostStatusWithoutProxies() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".validation")) {
            "FRP UI verification requires an isolated validation application ID"
        }
        val state = mutableStateOf(
            FrpState(
                phase = FrpPhase.RUNNING,
                loaded = true,
                message = "",
                connection = FrpConnectionStatus(FrpConnectionPhase.RETRYING, "login_rejected", 2)
            )
        )
        compose.setContent { MaterialTheme { FrpScreen(state.value, {}, {}, {}, {}) } }
        compose.onNodeWithText("連線失敗，重試中").assertIsDisplayed()
        compose.onNodeWithText("frps 拒絕登入", substring = true).assertIsDisplayed()
        compose.onNodeWithText("已嘗試連線 2 次").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(connection = FrpConnectionStatus(FrpConnectionPhase.CONNECTED)) }
        compose.onNodeWithText("已連線至 frps").assertIsDisplayed()
        compose.onNodeWithText("目前沒有代理狀態", substring = true).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(connection = FrpConnectionStatus(FrpConnectionPhase.RECONNECTING)) }
        compose.onNodeWithText("已斷線，重新連線中").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(connection = FrpConnectionStatus()) }
        compose.onNodeWithText("連線狀態無法取得").assertIsDisplayed()
    }
}
