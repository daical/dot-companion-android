package dev.dotcompanion.mobile

import dev.dotcompanion.core.CompanionMessage
import dev.dotcompanion.core.CompanionQueue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalBridgeClientTest {
    @Test fun cleartextIsLimitedToExplicitDebugEmulatorHost() {
        LocalBridgeClient.validateEndpoint("http://10.0.2.2:8787", true)
        LocalBridgeClient.validateEndpoint("https://example.invalid", false)
        listOf("http://10.0.2.2", "http://localhost", "https://user@example.invalid", "https://example.invalid?token=x", "https://example.invalid/mcp").forEach {
            assertThrows(IllegalArgumentException::class.java) { LocalBridgeClient.validateEndpoint(it, false) }
        }
        assertThrows(IllegalArgumentException::class.java) { LocalBridgeClient.validateEndpoint("http://10.0.2.2.example.invalid", true) }
    }
    private class Response(private val code: Int, private val body: String) : HttpURLConnection(URL("https://example.invalid")) {
        var disconnected = false
        val written = ByteArrayOutputStream()
        override fun getResponseCode() = code
        override fun getContentType() = "application/json"
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getOutputStream() = written
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
    }
    @Test fun redirectsAreRejectedAndAuthorizationDoesNotEnterTheUrl() = runTest {
        val connection = Response(302, "{}")
        val client = LocalBridgeClient("https://example.invalid", "synthetic-development-token", false) { uri ->
            assertNull(uri.query)
            assertNull(uri.userInfo)
            connection
        }
        try { client.verifyStatus(); fail("Redirect must be rejected") } catch (_: IllegalArgumentException) { }
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }
    @Test fun idempotentPostAcceptsAnAlreadyRepliedStatus() = runTest {
        val id = "9f13e909-fb28-479a-9b99-de401517eaa1"
        val connection = Response(200, "{\"requestId\":\"$id\",\"status\":\"replied\"}")
        val client = LocalBridgeClient("https://example.invalid", "synthetic-development-token", false) { connection }
        client.submit(CompanionMessage(id, CompanionQueue.CONVERSATION_ID, "An idempotent fixture", "2026-01-01T00:00:00Z"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }
}
