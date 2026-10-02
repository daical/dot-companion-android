package dev.dotcompanion.shared

import android.content.res.AssetManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class LicenseNotice(val id: String, val title: String, val text: String)
data class LicenseCatalog(val entries: List<LicenseNotice>)

sealed interface LicenseLoadState {
    data object Loading : LicenseLoadState
    data class Available(val catalog: LicenseCatalog) : LicenseLoadState
    data object Unavailable : LicenseLoadState
}

/** Reads only the notices bundled in this APK. No URI resolution or network access. */
object LicenseNoticeLoader {
    const val ASSET_PATH = "licenses/notices.json"
    const val MAX_ASSET_BYTES = 16 * 1024 * 1024
    const val MAX_ENTRIES = 1024
    const val MAX_ID_BYTES = 256
    const val MAX_TITLE_BYTES = 1024
    const val MAX_TEXT_BYTES = 4 * 1024 * 1024

    fun load(assets: AssetManager): LicenseCatalog = assets.open(ASSET_PATH).use(::read)

    fun read(input: InputStream): LicenseCatalog {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val single = input.read()
                if (single < 0) break
                require(bytes.size() < MAX_ASSET_BYTES) { "Notices exceed the asset limit" }
                bytes.write(single)
            } else {
                require(bytes.size() + count <= MAX_ASSET_BYTES) { "Notices exceed the asset limit" }
                bytes.write(buffer, 0, count)
            }
        }
        return decode(bytes.toByteArray())
    }

    fun decode(bytes: ByteArray): LicenseCatalog {
        require(bytes.size in 1..MAX_ASSET_BYTES) { "Invalid notice asset size" }
        val root = parseJsonObject(bytes)
        require(root.keys().asSequence().toSet() == setOf("schemaVersion", "entries")) { "Unexpected notice fields" }
        require(root.requiredInt("schemaVersion") == 1) { "Unsupported notice schema" }
        val entries = root.get("entries")
        require(entries is JSONArray && entries.length() in 1..MAX_ENTRIES) { "Invalid notice list" }
        val ids = HashSet<String>()
        return LicenseCatalog((0 until entries.length()).map { index ->
            val entry = entries.get(index)
            require(entry is JSONObject && entry.keys().asSequence().toSet() == setOf("id", "title", "text")) { "Invalid notice entry" }
            val id = entry.requiredString("id")
            val title = entry.requiredString("title")
            val text = entry.requiredString("text")
            require(id.isNotBlank() && utf8Size(id) <= MAX_ID_BYTES && id.none(Char::isISOControl) && ids.add(id)) { "Invalid notice ID" }
            require(title.isNotBlank() && utf8Size(title) <= MAX_TITLE_BYTES && title.none(Char::isISOControl)) { "Invalid notice title" }
            require(text.isNotBlank() && utf8Size(text) <= MAX_TEXT_BYTES && '\u0000' !in text) { "Invalid notice text" }
            LicenseNotice(id, title, text)
        })
    }

    private fun utf8Size(value: String): Int = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(value)).remaining()
}

@Composable
fun rememberPackagedLicenses(): State<LicenseLoadState> {
    val assets = LocalContext.current.applicationContext.assets
    return produceState<LicenseLoadState>(LicenseLoadState.Loading, assets) {
        value = withContext(Dispatchers.IO) {
            try { LicenseLoadState.Available(LicenseNoticeLoader.load(assets)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { LicenseLoadState.Unavailable }
        }
    }
}

/** Bounded text layouts preserve every character, including whitespace and surrogate pairs. */
fun licenseTextChunks(text: String): List<String> {
    val chunks = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        var end = minOf(start + 4096, text.length)
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end -= 1
        chunks += text.substring(start, end)
        start = end
    }
    return chunks
}
