package dev.dotcompanion.mobile

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.dotcompanion.shared.LicenseCatalog
import dev.dotcompanion.shared.LicenseLoadState
import dev.dotcompanion.shared.LicenseNotice
import dev.dotcompanion.shared.LicenseNoticeLoader
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
class LicenseScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val text = "Synthetic license fixture.\n\n  Indented fixture text.\nFinal fixture line.\n"
    private val notice = LicenseNotice("synthetic-fixture", "Synthetic fixture notice", text)
    private fun screenshot() {
        val bitmap = rule.runOnIdle {
            val content = rule.activity.findViewById<View>(android.R.id.content)
            assertTrue(content.width > 0 && content.height > 0)
            Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888).also { content.draw(Canvas(it)) }
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("License screenshot must show native rendered content", pixels.asSequence().filter { Color.alpha(it) > 0 }.distinct().take(8).count() == 8)
        val target = File("build/outputs/screenshots/phone-licenses.png")
        val directory = requireNotNull(target.parentFile)
        assertTrue(directory.isDirectory || directory.mkdirs())
        target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun offlineListOpensFullSelectableTextCopiesItVerbatimAndReturns() {
        var exited = 0
        rule.setContent { PhoneTheme { PhoneLicenseScreen(LicenseLoadState.Available(LicenseCatalog(listOf(notice))), { exited += 1 }) } }
        rule.onNodeWithText(notice.title).performClick()
        rule.onNodeWithTag("phone_license_text_0").assertTextEquals(text)
        screenshot()
        rule.onNodeWithTag("phone_license_copy").performClick()
        rule.runOnIdle {
            val clipboard = rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(text, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        rule.onNodeWithTag("phone_license_back").performClick()
        rule.onNodeWithTag("phone_license_list").assertIsDisplayed()
        rule.onNodeWithText(notice.title).assertIsDisplayed()
        rule.onNodeWithTag("phone_license_back").performClick()
        assertEquals(1, exited)
    }
    @Test fun loadingAndMissingNoticesHaveExplicitNativeStates() {
        val state = mutableStateOf<LicenseLoadState>(LicenseLoadState.Loading)
        rule.setContent { PhoneTheme { PhoneLicenseScreen(state.value, {}) } }
        rule.onNodeWithTag("phone_license_loading").assertIsDisplayed()
        rule.runOnIdle { state.value = LicenseLoadState.Unavailable }
        rule.onNodeWithTag("phone_license_error").assertIsDisplayed()
    }
    @Test fun openingPackagedLicensesFromSettingsKeepsTheUnsentChatDraft() {
        rule.setContent { PhoneScreen(PhoneUiState(PersistentSnapshot()), {}, {}, { _, _ -> }, {}, {}, false, false) }
        rule.onNodeWithTag("message_input").performTextInput("Keep this unsent draft")
        rule.onNodeWithTag("connection_settings").performClick()
        rule.onNodeWithTag("phone_licenses").performClick()
        rule.onNodeWithTag("phone_license_screen").assertIsDisplayed()
        rule.onNodeWithTag("phone_license_back").performClick()
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithTag("message_input").assertTextContains("Keep this unsent draft")
    }
    @Test fun generatedApkNoticesAreReadableWithoutNetworkAccess() {
        val catalog = LicenseNoticeLoader.load(rule.activity.assets)
        assertTrue(catalog.entries.isNotEmpty())
        assertTrue(catalog.entries.all { it.id.isNotBlank() && it.title.isNotBlank() && it.text.isNotBlank() })
    }
}
