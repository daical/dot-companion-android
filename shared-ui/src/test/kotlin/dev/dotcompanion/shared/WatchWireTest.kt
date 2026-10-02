package dev.dotcompanion.shared

import dev.dotcompanion.core.CompanionMessage
import dev.dotcompanion.core.CompanionQueue
import dev.dotcompanion.core.ConnectionMode
import dev.dotcompanion.core.FailureKind
import dev.dotcompanion.core.MessageStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WatchWireTest {
    private val message = CompanionMessage("9f13e909-fb28-479a-9b99-de401517eaa1", CompanionQueue.CONVERSATION_ID,
        "A local test request", "2026-01-01T00:00:00Z")

    @Test fun requestRoundTripHasOnlyTheAllowlistedFields() {
        val bytes = WatchWire.request(message)
        assertEquals(message.requestId, WatchWire.decodeRequest(bytes).requestId)
        assertEquals(setOf("schemaVersion", "requestId", "text", "createdAt"), JSONObject(String(bytes)).keys().asSequence().toSet())
    }
    @Test fun coercibleVersionsAndNonStringTextAreRejected() {
        listOf<Any>("1", 1.9, true).forEach { bad ->
            val json = JSONObject(String(WatchWire.request(message))).put("schemaVersion", bad)
            assertThrows(IllegalArgumentException::class.java) { WatchWire.decodeRequest(json.toString().toByteArray()) }
        }
        val json = JSONObject(String(WatchWire.request(message))).put("text", 123)
        assertThrows(IllegalArgumentException::class.java) { WatchWire.decodeRequest(json.toString().toByteArray()) }
    }
    @Test fun credentialsUnknownFieldsAndOversizedPacketsAreRejected() {
        val json = JSONObject(String(WatchWire.request(message))).put("token", "synthetic-untrusted-value")
        assertThrows(IllegalArgumentException::class.java) { WatchWire.decodeRequest(json.toString().toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { WatchWire.decodeRequest(ByteArray(WatchWire.MAX_PACKET_BYTES + 1)) }
    }
    @Test fun receiptIsPersistentQueueingAndNeverAReply() {
        val json = JSONObject(String(WatchWire.receipt(message.requestId)))
        assertEquals("durably_queued", json.getString("phase"))
        assertEquals(message.requestId, WatchWire.decodeReceipt(json.toString().toByteArray()).requestId)
        json.put("phase", "replied")
        assertThrows(IllegalArgumentException::class.java) { WatchWire.decodeReceipt(json.toString().toByteArray()) }
    }
    @Test fun stateRequiresStrictBooleanReplyTypesAndFailureProvenance() {
        val json = JSONObject(String(WatchWire.state(message, ConnectionMode.LOCAL_BRIDGE, true)))
        json.put("online", "true")
        assertThrows(IllegalArgumentException::class.java) { WatchWire.decodeState(json.toString().toByteArray()) }
        json.put("online", true).put("reply", 123)
        assertThrows(IllegalArgumentException::class.java) { WatchWire.decodeState(json.toString().toByteArray()) }
        val failed = message.copy(status = MessageStatus.FAILED, failureKind = FailureKind.REMOTE)
        assertEquals(FailureKind.REMOTE, WatchWire.decodeState(WatchWire.state(failed, ConnectionMode.LOCAL_BRIDGE, true)).failureKind)
    }
    @Test fun trailingDataAndMalformedUtf8AreRejected() {
        assertThrows(IllegalArgumentException::class.java) { WatchWire.decodeRequest(WatchWire.request(message) + "{}".toByteArray()) }
        assertThrows(Exception::class.java) { WatchWire.decodeRequest(byteArrayOf(0xc3.toByte(), 0x28)) }
    }
}
