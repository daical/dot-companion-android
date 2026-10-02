package dev.dotcompanion.shared

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LicenseNoticesTest {
    private val fullText = "Synthetic license fixture.\r\n\r\n  Indented line with Ω and 🌱.\nFinal fixture line.\n"
    private fun asset(): JSONObject = JSONObject().put("schemaVersion", 1).put("entries", JSONArray().put(
        JSONObject().put("id", "synthetic-fixture").put("title", "Synthetic fixture notice").put("text", fullText)))

    @Test @Config(sdk = [26]) fun boundedFixtureReadPreservesTitleAndEveryTextCharacterOnMinimumPhoneSdk() {
        val input = requireNotNull(javaClass.classLoader?.getResourceAsStream("licenses/synthetic-notices.json"))
        val catalog = input.use(LicenseNoticeLoader::read)
        assertEquals(1, catalog.entries.size)
        assertEquals("Synthetic fixture notice", catalog.entries.single().title)
        assertEquals(fullText, catalog.entries.single().text)
    }
    @Test fun schemaTypesUnknownFieldsAndDuplicateIdsAreRejected() {
        listOf<Any>("1", 1.5, true).forEach { version ->
            assertThrows(IllegalArgumentException::class.java) { LicenseNoticeLoader.decode(asset().put("schemaVersion", version).toString().toByteArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) { LicenseNoticeLoader.decode(asset().put("url", "https://example.invalid").toString().toByteArray()) }
        val duplicate = asset()
        duplicate.getJSONArray("entries").put(duplicate.getJSONArray("entries").getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { LicenseNoticeLoader.decode(duplicate.toString().toByteArray()) }
        val bad = asset()
        bad.getJSONArray("entries").getJSONObject(0).put("text", 123)
        assertThrows(IllegalArgumentException::class.java) { LicenseNoticeLoader.decode(bad.toString().toByteArray()) }
    }
    @Test fun invalidTextEncodingEmptyListsAndOversizedAssetsFailExplicitly() {
        assertThrows(Exception::class.java) { LicenseNoticeLoader.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(IllegalArgumentException::class.java) { LicenseNoticeLoader.decode(asset().toString().toByteArray() + "{}".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { LicenseNoticeLoader.decode(asset().put("entries", JSONArray()).toString().toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { LicenseNoticeLoader.read(ByteArray(LicenseNoticeLoader.MAX_ASSET_BYTES + 1).inputStream()) }
        val bad = asset()
        bad.getJSONArray("entries").getJSONObject(0).put("text", "invalid\u0000text")
        assertThrows(IllegalArgumentException::class.java) { LicenseNoticeLoader.decode(bad.toString().toByteArray()) }
    }
    @Test fun boundedLayoutsRetainFullTextWhitespaceAndUnicodePairs() {
        val text = " ".repeat(4095) + "🌱" + "\r\n" + fullText.repeat(120)
        val chunks = licenseTextChunks(text)
        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 4096 && !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }
}
