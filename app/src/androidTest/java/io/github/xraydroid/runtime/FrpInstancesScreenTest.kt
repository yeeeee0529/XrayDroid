package io.github.xraydroid.runtime

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xraydroid.R
import io.github.xraydroid.ui.FrpInstancesScreen
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrpInstancesScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun creatingRenamingAndOpeningInstancesPreservesTheirIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".validation")) {
            "FRP instance verification requires an isolated validation application ID"
        }
        val suffix = UUID.randomUUID().toString().take(8)
        val firstName = "Fixture first $suffix"
        val secondName = "Fixture second $suffix"
        val renamed = "Fixture renamed $suffix"
        val opened = mutableListOf<String>()
        FrpStore.initialize(context)
        compose.setContent {
            val instances by FrpStore.instances.collectAsState()
            val loaded by FrpStore.instancesLoaded.collectAsState()
            MaterialTheme {
                FrpInstancesScreen(instances, loaded, {}, { opened += it })
            }
        }
        compose.waitUntil(10_000) { FrpStore.instancesLoaded.value }

        fun create(name: String): String {
            scrollToText(context.getString(R.string.frp_instance_add))
            compose.onNodeWithText(context.getString(R.string.frp_instance_add)).performClick()
            compose.onNodeWithText(context.getString(R.string.frp_instance_name)).performTextReplacement(name)
            compose.onNodeWithText(context.getString(R.string.frp_instance_create)).performClick()
            compose.waitUntil(10_000) { FrpStore.instances.value.any { it.name == name } }
            val id = FrpStore.instances.value.single { it.name == name }.id
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(id, opened.last()) }
            return id
        }

        val firstId = create(firstName)
        val secondId = create(secondName)
        assertNotEquals(firstId, secondId)
        scrollToText(firstName)
        compose.onNode(
            hasText(context.getString(R.string.frp_instance_rename)) and
                hasAnyAncestor(hasAnyChild(hasText(firstName))),
            useUnmergedTree = true
        ).performClick()
        compose.onNodeWithText(context.getString(R.string.frp_instance_name)).performTextReplacement(renamed)
        compose.onNodeWithText(context.getString(R.string.frp_instance_save_name)).performClick()
        compose.waitUntil(10_000) { FrpStore.instances.value.any { it.id == firstId && it.name == renamed } }
        compose.waitForIdle()
        compose.onNodeWithText(firstName).assertDoesNotExist()
        assertEquals(secondName, FrpStore.instances.value.single { it.id == secondId }.name)

        scrollToText(renamed)
        compose.onNodeWithText(renamed).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(firstId, opened.last()) }
        scrollToText(secondName)
        compose.onNodeWithText(secondName).performClick()
        compose.runOnIdle { assertEquals(secondId, opened.last()) }
    }

    private fun scrollToText(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    }
}
