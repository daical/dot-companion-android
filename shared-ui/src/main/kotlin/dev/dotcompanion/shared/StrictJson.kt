package dev.dotcompanion.shared

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONObject
import org.json.JSONTokener

fun parseJsonObject(bytes: ByteArray): JSONObject {
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    val tokener = JSONTokener(text)
    val value = tokener.nextValue()
    require(value is JSONObject && tokener.nextClean() == '\u0000') { "Expected a single JSON object" }
    return value
}

fun JSONObject.requiredString(key: String): String = get(key).let {
    require(it is String) { "Expected a string field" }; it
}
fun JSONObject.nullableString(key: String): String? = get(key).let {
    require(it === JSONObject.NULL || it is String) { "Expected a string or null field" }
    if (it === JSONObject.NULL) null else it as String
}
fun JSONObject.requiredInt(key: String): Int = get(key).let {
    require(it is Int) { "Expected an integer field" }; it
}
fun JSONObject.requiredLong(key: String): Long = get(key).let {
    require(it is Int || it is Long) { "Expected an integer field" }; (it as Number).toLong()
}
fun JSONObject.requiredBoolean(key: String): Boolean = get(key).let {
    require(it is Boolean) { "Expected a boolean field" }; it
}
