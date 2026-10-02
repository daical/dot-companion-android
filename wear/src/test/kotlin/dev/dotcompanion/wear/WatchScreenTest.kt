package dev.dotcompanion.wear

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
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
    private fun scrollTo(matcher: SemanticsMatcher) {
        // ScalingLazyColumn applies its modifier to a wrapper. Its inner lazy list
        // owns the scroll actions; target that actual descendant in the full tree.
        rule.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag("watch_list")), useUnmergedTree = true)
            .performScrollToNode(matcher)
    }
    private fun assertInsideRoundScreen(tag: String) {
        val screen = rule.onNodeWithTag("watch_screen").fetchSemanticsNode().boundsInRoot
        val bounds = rule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val radius = minOf(screen.width, screen.height) / 2f
        for (corner in listOf(bounds.topLeft, bounds.topRight, bounds.bottomLeft, bounds.bottomRight)) {
            val dx = corner.x - screen.center.x
            val dy = corner.y - screen.center.y
            assertTrue("$tag must be fully readable inside the round viewport", dx * dx + dy * dy <= radius * radius)
        }
    }
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
        assertInsideRoundScreen("watch_connection_status")
        assertInsideRoundScreen("watch_live_status")
        rule.onNodeWithTag("watch_live_status").assertTextContains("unavailable", substring = true)
        screenshot("watch-round-preview")
        scrollTo(hasTestTag("watch_synthetic_fixture"))
        rule.onNodeWithTag("watch_synthetic_fixture").performClick()
        assertEquals(listOf("Show me the synthetic watch preview."), sent)
    }
    @Test fun phoneReceiptAndMissingAckStateDoNotBecomeReplies() {
        val state = PersistentSnapshot(CompanionState(ConnectionMode.LOCAL_BRIDGE, listOf(message)), phoneAcknowledgedIds = setOf(message.requestId))
        val value = mutableStateOf(WatchUiState(state, phoneReachable = true))
        rule.setContent { WatchScreen(value.value, {}, {}, {}, {}, false) }
        scrollTo(hasText("Saved on phone · no reply yet"))
        rule.onNodeWithText("Saved on phone · no reply yet").assertIsDisplayed()
        screenshot("watch-round-receipt")
        rule.runOnIdle { value.value = value.value.copy(saved = value.value.saved.copy(phoneAcknowledgedIds = emptySet())) }
        scrollTo(hasText("Phone reports waiting · receipt unconfirmed"))
        rule.onNodeWithText("Phone reports waiting · receipt unconfirmed").assertIsDisplayed()
    }
    @Test fun failedEnqueueRetainsTheTypedDraftAndComposer() {
        val value = mutableStateOf(WatchUiState(PersistentSnapshot()))
        rule.setContent { WatchScreen(value.value, { value.value = value.value.copy(issue = "Fixture storage failure") }, {}, {}, {}, false) }
        scrollTo(hasTestTag("watch_write_text"))
        rule.onNodeWithTag("watch_write_text").performClick()
        scrollTo(hasTestTag("watch_message_input"))
        rule.onNodeWithTag("watch_message_input").performTextInput("Keep this draft")
        scrollTo(hasTestTag("watch_send_text"))
        rule.onNodeWithTag("watch_send_text").performClick()
        scrollTo(hasTestTag("watch_message_input"))
        rule.onNodeWithTag("watch_message_input").assertTextContains("Keep this draft")
    }
    @Test fun knownPausedAndOfflinePhoneStatesAreVisible() {
        val value = mutableStateOf(WatchUiState(PersistentSnapshot(CompanionState(ConnectionMode.LOCAL_BRIDGE)), phoneReachable = true, phoneMode = ConnectionMode.DISCONNECTED))
        rule.setContent { WatchScreen(value.value, {}, {}, {}, {}, false) }
        assertInsideRoundScreen("watch_connection_status")
        assertInsideRoundScreen("watch_live_status")
        rule.onNodeWithTag("watch_connection_status").assertTextContains("Phone paused", substring = true)
        rule.runOnIdle { value.value = value.value.copy(phoneMode = ConnectionMode.LOCAL_BRIDGE, phoneOnline = false) }
        assertInsideRoundScreen("watch_connection_status")
        assertInsideRoundScreen("watch_live_status")
        rule.onNodeWithTag("watch_connection_status").assertTextContains("Phone network offline", substring = true)
    }
}
