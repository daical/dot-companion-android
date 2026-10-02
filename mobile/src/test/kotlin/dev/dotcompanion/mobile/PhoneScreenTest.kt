package dev.dotcompanion.mobile

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
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
        val bitmap = rule.onNodeWithTag("phone_screen").captureToImage().asAndroidBitmap()
        val target = File("build/outputs/screenshots/$name.png").apply { parentFile.mkdirs() }
        target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
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
        rule.onNodeWithTag("settings_live_status").assertTextContains("unavailable")
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
