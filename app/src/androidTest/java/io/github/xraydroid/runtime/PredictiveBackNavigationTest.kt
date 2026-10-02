package io.github.xraydroid.runtime

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.xraydroid.ui.PredictiveBackNavigation
import io.github.xraydroid.ui.theme.XrayDroidTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PredictiveBackNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var destination = "editor"
    private var compositions = 0
    private var previewCompositions = 0

    private fun showNavigation() {
        compose.setContent {
            var page by remember { mutableStateOf("editor") }
            XrayDroidTheme {
                PredictiveBackNavigation(page, { if (it == "editor") "home" else null }, {
                    destination = it
                    page = it
                }) { current, back ->
                    val instance = remember {
                        if (current == "editor") {
                            ++compositions
                        } else {
                            previewCompositions++
                            0
                        }
                    }
                    var draft by remember { mutableStateOf("original") }
                    Column {
                        Text("$current:$instance:$draft")
                        Button(onClick = { draft = "edited" }) { Text("Edit") }
                        Button(onClick = back) { Text("Back") }
                    }
                }
            }
        }
    }

    private fun startGesture() {
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            compose.activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(120f, 0f, 0.6f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
    }

    @Test
    fun cancelledGesturePreservesDraftAndComposition() {
        showNavigation()
        compose.onNodeWithText("Edit").performClick()
        startGesture()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        compose.onNodeWithText("editor:1:edited").assertIsDisplayed()
        assertEquals(1, compositions)
        assertEquals("editor", destination)
        // 回復完成後再開始手勢，確認沒有殘留進度或舊工作清除新預覽。
        startGesture()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals("home", destination)
    }

    @Test
    fun commitWaitsForAnimationAndKeepsPreviewComposition() {
        showNavigation()
        startGesture()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { assertEquals("editor", destination) }
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { assertEquals("home", destination) }
        assertEquals(1, compositions)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("home:0:original").assertIsDisplayed()
        assertEquals(1, previewCompositions)
    }

    @Test
    fun toolbarBackCompletesNavigation() {
        showNavigation()
        compose.onNodeWithText("Back").performClick()
        compose.waitForIdle()
        assertEquals("home", destination)
        assertTrue(!compose.activity.isFinishing)
    }

    @Test
    fun newGestureInterruptsCancellationWithoutLosingDraft() {
        showNavigation()
        compose.onNodeWithText("Edit").performClick()
        startGesture()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeBy(32)
        startGesture()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertEquals("home", destination) }
        assertEquals(1, compositions)
        compose.mainClock.autoAdvance = true
    }
}
