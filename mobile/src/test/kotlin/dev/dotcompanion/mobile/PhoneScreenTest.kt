package dev.dotcompanion.mobile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dev.dotcompanion.core.CompanionMessage
import dev.dotcompanion.core.CompanionQueue
import dev.dotcompanion.core.CompanionState
import dev.dotcompanion.core.ConnectionMode
import dev.dotcompanion.core.FailureKind
import dev.dotcompanion.core.MessageStatus
import dev.dotcompanion.core.ReplyProvenance
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
@Config(application = Application::class, sdk = [35], qualifiers = "w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val message = CompanionMessage("9f13e909-fb28-479a-9b99-de401517eaa1", CompanionQueue.CONVERSATION_ID,
        "This is a local fixture request.", "2026-01-01T00:00:00Z")
    private fun screenshot(name: String) {
        // Compose's window capture waits for a redraw callback unavailable in Robolectric.
        // Draw the actual laid-out Android content view with native graphics instead.
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
    @Test fun syntheticFixtureControlAndNativePreviewAreExplicit() {
        val sends = mutableListOf<String>()
        rule.setContent { PhoneScreen(PhoneUiState(PersistentSnapshot()), { sends += it }, {}, { _, _ -> }, {}, {}, false, animateCompanion = false) }
        rule.onNodeWithText("Live dot connection: unavailable").assertIsDisplayed()
        rule.onNodeWithTag("synthetic_fixture").performClick()
        assertEquals(listOf("Show me this local preview."), sends)
        rule.onNodeWithTag("voice_unavailable").assertIsDisplayed()
        screenshot("phone-preview")
    }
    @Test fun modeSwitchPreservesSyntheticReplyLabelAndSettingsDisclosesBoundary() {
        val replied = message.copy(status = MessageStatus.REPLIED, reply = "[Synthetic fixture] A locally generated test response.", replyProvenance = ReplyProvenance.LOCAL_SYNTHETIC)
        val value = mutableStateOf(PhoneUiState(PersistentSnapshot(CompanionState(ConnectionMode.LOCAL_BRIDGE, listOf(replied)), syntheticRequestIds = setOf(message.requestId))))
        rule.setContent { PhoneScreen(value.value, {}, { mode -> value.value = value.value.copy(saved = value.value.saved.copy(state = value.value.saved.state.copy(mode = mode))) }, { _, _ -> }, {}, {}, false, false) }
        rule.onNodeWithTag("phone_messages").performScrollToNode(hasText("Synthetic fixture · generated on device"))
        rule.onNodeWithText("Synthetic fixture · generated on device").assertIsDisplayed()
        rule.onNodeWithTag("connection_settings").performClick()
        rule.onNodeWithTag("settings_live_status").assertTextContains("unavailable", substring = true)
        rule.onNodeWithTag("mode_disconnected").performClick()
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithTag("phone_messages").performScrollToNode(hasText("Synthetic fixture · generated on device"))
        rule.onNodeWithText("Synthetic fixture · generated on device").assertIsDisplayed()
    }
    @Test fun remoteFailureDoesNotOfferAnIneffectiveTransportRetry() {
        val failed = message.copy(status = MessageStatus.FAILED, failureKind = FailureKind.REMOTE)
        rule.setContent { PhoneScreen(PhoneUiState(PersistentSnapshot(CompanionState(ConnectionMode.LOCAL_BRIDGE, listOf(failed)))), {}, {}, { _, _ -> }, {}, {}, false, false) }
        rule.onNodeWithTag("phone_messages").performScrollToNode(hasText("Bridge delivery failed · recover the subscription, then send a new request"))
        rule.onNodeWithText("Retry").assertDoesNotExist()
    }
    @Test fun waitingNativeScreenDoesNotShowAReplyOrClaimLiveConnection() {
        val waiting = message.copy(status = MessageStatus.AWAITING_REPLY)
        rule.setContent { PhoneScreen(PhoneUiState(PersistentSnapshot(CompanionState(ConnectionMode.LOCAL_BRIDGE, listOf(waiting)))), {}, {}, { _, _ -> }, {}, {}, false, false) }
        rule.onNodeWithText("Waiting for reply").assertIsDisplayed()
        rule.onNodeWithText("Live dot connection: unavailable").assertIsDisplayed()
        screenshot("phone-waiting")
    }
}
