package dev.dotcompanion.wear

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import dev.dotcompanion.core.CompanionMessage
import dev.dotcompanion.core.CompanionQueue
import dev.dotcompanion.core.CompanionState
import dev.dotcompanion.core.ConnectionMode
import dev.dotcompanion.core.MessageStatus
import dev.dotcompanion.shared.PersistentSnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w192dp-h192dp-round-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val message = CompanionMessage("9f13e909-fb28-479a-9b99-de401517eaa1", CompanionQueue.CONVERSATION_ID,
        "A short local request", "2026-01-01T00:00:00Z", status = MessageStatus.AWAITING_REPLY)
    private fun screenshot(name: String) {
        val bitmap = rule.onNodeWithTag("watch_screen").captureToImage().asAndroidBitmap()
        val target = File("build/outputs/screenshots/$name.png").apply { parentFile.mkdirs() }
        target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
    }
    @Test fun roundSmallScreenRendersNativePreviewAndExplicitFixtureControl() {
        val sent = mutableListOf<String>()
        rule.setContent { WatchScreen(WatchUiState(PersistentSnapshot()), { sent += it }, {}, {}, {}, false) }
        screenshot("watch-round-preview")
        rule.onNodeWithTag("watch_list").performScrollToNode(hasTestTag("watch_live_status"))
        rule.onNodeWithTag("watch_live_status").assertIsDisplayed().assertTextContains("unavailable")
        rule.onNodeWithTag("watch_list").performScrollToNode(hasTestTag("watch_synthetic_fixture"))
        rule.onNodeWithTag("watch_synthetic_fixture").performClick()
        assertEquals(listOf("Show me the synthetic watch preview."), sent)
    }
    @Test fun phoneReceiptAndMissingAckStateDoNotBecomeReplies() {
        val state = PersistentSnapshot(CompanionState(ConnectionMode.LOCAL_BRIDGE, listOf(message)), phoneAcknowledgedIds = setOf(message.requestId))
        val value = mutableStateOf(WatchUiState(state, phoneReachable = true))
        rule.setContent { WatchScreen(value.value, {}, {}, {}, {}, false) }
        rule.onNodeWithTag("watch_list").performScrollToNode(hasText("Saved on phone · no reply yet"))
        rule.onNodeWithText("Saved on phone · no reply yet").assertIsDisplayed()
        screenshot("watch-round-receipt")
        rule.runOnIdle { value.value = value.value.copy(saved = value.value.saved.copy(phoneAcknowledgedIds = emptySet())) }
        rule.onNodeWithText("Phone reports waiting · receipt unconfirmed").assertIsDisplayed()
    }
    @Test fun failedEnqueueRetainsTheTypedDraftAndComposer() {
        val value = mutableStateOf(WatchUiState(PersistentSnapshot()))
        rule.setContent { WatchScreen(value.value, { value.value = value.value.copy(issue = "Fixture storage failure") }, {}, {}, {}, false) }
        rule.onNodeWithTag("watch_list").performScrollToNode(hasTestTag("watch_write_text"))
        rule.onNodeWithTag("watch_write_text").performClick()
        rule.onNodeWithTag("watch_list").performScrollToNode(hasTestTag("watch_message_input"))
        rule.onNodeWithTag("watch_message_input").performTextInput("Keep this draft")
        rule.onNodeWithTag("watch_list").performScrollToNode(hasTestTag("watch_send_text"))
        rule.onNodeWithTag("watch_send_text").performClick()
        rule.onNodeWithTag("watch_list").performScrollToNode(hasTestTag("watch_message_input"))
        rule.onNodeWithTag("watch_message_input").assertTextContains("Keep this draft")
    }
    @Test fun knownPausedAndOfflinePhoneStatesAreVisible() {
        val value = mutableStateOf(WatchUiState(PersistentSnapshot(CompanionState(ConnectionMode.LOCAL_BRIDGE)), phoneReachable = true, phoneMode = ConnectionMode.DISCONNECTED))
        rule.setContent { WatchScreen(value.value, {}, {}, {}, {}, false) }
        rule.onNodeWithTag("watch_list").performScrollToNode(hasTestTag("watch_connection_status"))
        rule.onNodeWithTag("watch_connection_status").assertTextContains("Phone paused")
        rule.runOnIdle { value.value = value.value.copy(phoneMode = ConnectionMode.LOCAL_BRIDGE, phoneOnline = false) }
        rule.onNodeWithTag("watch_connection_status").assertTextContains("Phone network offline")
    }
}
