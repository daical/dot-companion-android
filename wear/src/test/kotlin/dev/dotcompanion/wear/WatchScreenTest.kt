package dev.dotcompanion.wear

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
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
        // Preserve the configured round-screen layout while drawing the actual view.
        // Native View/Canvas rendering avoids Compose's unsupported redraw wait here.
        val bitmap = rule.runOnIdle {
            val content = rule.activity.findViewById<View>(android.R.id.content)
            assertTrue("Native content must be laid out", content.width > 0 && content.height > 0)
            Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888).also {
                content.draw(Canvas(it))
            }
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val colors = HashSet<Int>()
        for (pixel in pixels) {
            if (Color.alpha(pixel) > 0) colors += pixel
            if (colors.size >= 8) break
        }
        assertTrue("Native capture must contain rendered UI, not a blank bitmap", colors.size >= 8)
        val target = File("build/outputs/screenshots/$name.png")
        val directory = requireNotNull(target.parentFile)
        assertTrue("Screenshot directory must exist", directory.isDirectory || directory.mkdirs())
        target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    @Test fun roundSmallScreenRendersNativePreviewAndExplicitFixtureControl() {
        val sent = mutableListOf<String>()
        rule.setContent { WatchScreen(WatchUiState(PersistentSnapshot()), { sent += it }, {}, {}, {}, false) }
        screenshot("watch-round-preview")
        rule.onNodeWithTag("watch_list").performScrollToNode(hasTestTag("watch_live_status"))
        rule.onNodeWithTag("watch_live_status").assertIsDisplayed().assertTextContains("unavailable", substring = true)
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
        rule.onNodeWithTag("watch_connection_status").assertTextContains("Phone paused", substring = true)
        rule.runOnIdle { value.value = value.value.copy(phoneMode = ConnectionMode.LOCAL_BRIDGE, phoneOnline = false) }
        rule.onNodeWithTag("watch_connection_status").assertTextContains("Phone network offline", substring = true)
    }
}
