package io.github.xraydroid.runtime

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xraydroid.R
import io.github.xraydroid.ui.FrpScreen
import org.junit.Rule
import org.junit.Test

class FrpConnectionScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun topCardShowsLoginFailureRecoveryAndLostStatusWithoutProxies() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".validation")) {
            "FRP UI verification requires an isolated validation application ID"
        }
        val state = mutableStateOf(
            FrpState(
                phase = FrpPhase.RUNNING,
                loaded = true,
                message = null,
                connection = FrpConnectionStatus(FrpConnectionPhase.RETRYING, "login_rejected", 2)
            )
        )
        compose.setContent { MaterialTheme { FrpScreen(state.value, {}, {}, {}, {}) } }
        compose.onNodeWithText(context.getString(R.string.frp_connection_retrying)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.frp_connection_error_login_rejected)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(
            context.resources.getQuantityString(R.plurals.frp_connection_attempts, 2, 2)
        ).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(connection = FrpConnectionStatus(FrpConnectionPhase.CONNECTED)) }
        compose.onNodeWithText(context.getString(R.string.frp_connection_connected)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.frp_proxies_empty)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(connection = FrpConnectionStatus(FrpConnectionPhase.RECONNECTING)) }
        compose.onNodeWithText(context.getString(R.string.frp_connection_reconnecting)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(connection = FrpConnectionStatus()) }
        compose.onNodeWithText(context.getString(R.string.frp_connection_unknown)).performScrollTo().assertIsDisplayed()
    }
}
