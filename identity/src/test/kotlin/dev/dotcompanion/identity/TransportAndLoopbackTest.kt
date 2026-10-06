package dev.dotcompanion.identity

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

private class FakeConnection(uri: URI, private val status: Int = 200, private val response: ByteArray = "{}".toByteArray()) : HttpURLConnection(uri.toURL()) {
    val posted = ByteArrayOutputStream()
    var disconnected = false
    var inputRead = false
    var length = -1L
    override fun getResponseCode(): Int = status
    override fun getContentLengthLong(): Long = length
    override fun getInputStream(): InputStream { inputRead = true; return ByteArrayInputStream(response) }
    override fun getOutputStream(): OutputStream = posted
    override fun connect() { }
    override fun disconnect() { disconnected = true }
    override fun usingProxy(): Boolean = false
}

class BoundedHttpsTransportTest {
    private val tokenUri = URI("${OpenAiSignIn.ISSUER}/api/accounts/oauth/token")

    @Test fun `HTTPS transport bounds requests and disables redirects and caches`() {
        val connection = FakeConnection(tokenUri)
        val transport = BoundedHttpsTransport(connectionFactory = { connection })
        val response = transport.execute(OAuthHttpRequest(tokenUri, "POST", "code=synthetic-secret"))
        assertEquals(200, response.statusCode)
        assertEquals("{}", response.body)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals(10_000, connection.connectTimeout)
        assertTrue(connection.readTimeout in 1..10_000)
        assertEquals("application/x-www-form-urlencoded", connection.getRequestProperty("Content-Type"))
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertEquals("code=synthetic-secret", connection.posted.toString("UTF-8"))
        assertTrue(connection.disconnected)
    }

    @Test fun `redirect and error payloads are neither followed nor retained`() {
        for (status in listOf(302, 307, 400, 500)) {
            val connection = FakeConnection(tokenUri, status, "synthetic-secret-error".toByteArray())
            var count = 0
            val response = BoundedHttpsTransport(connectionFactory = { count++; connection }).execute(OAuthHttpRequest(tokenUri))
            assertEquals(status, response.statusCode)
            assertEquals("", response.body)
            assertEquals(1, count)
            assertFalse(connection.inputRead)
            assertFalse(connection.instanceFollowRedirects)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun `unsafe endpoint or request is rejected before any connection`() {
        var connections = 0
        val transport = BoundedHttpsTransport(connectionFactory = { connections++; FakeConnection(it) })
        for (uri in listOf("http://auth.openai.com/token", "https://api.openai.com/v1", "https://auth.openai.com.evil.test/token",
            "https://user@auth.openai.com/token", "https://auth.openai.com:444/token", "https://auth.openai.com/token?x=1")) {
            expectFailure(SignInFailure.TRANSPORT_FAILURE) { transport.execute(OAuthHttpRequest(URI(uri))) }
        }
        expectFailure(SignInFailure.TRANSPORT_FAILURE) { transport.execute(OAuthHttpRequest(tokenUri, "POST")) }
        expectFailure(SignInFailure.TRANSPORT_FAILURE) { transport.execute(OAuthHttpRequest(tokenUri, "GET", "secret")) }
        expectFailure(SignInFailure.TRANSPORT_FAILURE) { transport.execute(OAuthHttpRequest(tokenUri, "DELETE")) }
        expectFailure(SignInFailure.TRANSPORT_FAILURE) { transport.execute(OAuthHttpRequest(tokenUri, "POST", "x".repeat(65_537))) }
        assertEquals(0, connections)
    }

    @Test fun `oversized announced and streaming bodies fail with sanitized errors`() {
        for (announced in listOf(false, true)) {
            val connection = FakeConnection(tokenUri, 200, ByteArray(65) { 'x'.code.toByte() })
            if (announced) connection.length = 65
            val transport = BoundedHttpsTransport(maxResponseBytes = 64, connectionFactory = { connection })
            expectFailure(SignInFailure.TRANSPORT_FAILURE) { transport.execute(OAuthHttpRequest(tokenUri)) }
            assertTrue(connection.disconnected)
            if (announced) assertFalse(connection.inputRead)
        }
        val malformed = FakeConnection(tokenUri, 200, byteArrayOf(0xC3.toByte(), 0x28))
        expectFailure(SignInFailure.TRANSPORT_FAILURE) {
            BoundedHttpsTransport(connectionFactory = { malformed }).execute(OAuthHttpRequest(tokenUri))
        }
    }

    @Test fun `raw connection exceptions cannot leak request secrets`() {
        val failure = expectFailure(SignInFailure.TRANSPORT_FAILURE) {
            BoundedHttpsTransport(connectionFactory = { throw IllegalStateException("synthetic-secret") }).execute(OAuthHttpRequest(tokenUri))
        }
        assertFalse(failure.toString().contains("synthetic-secret"))
        assertNull(failure.cause)
    }
}

class LoopbackCallbackListenerTest {
    @Test fun `real IPv4 loopback callback returns code and generic browser acknowledgement`() {
        LoopbackCallbackListener().use { listener ->
            assertEquals("127.0.0.1", listener.redirectUri.host)
            assertTrue(listener.redirectUri.port > 0)
            val pending = OpenAiSignIn.createAttempt(TEST_HOST, listener.redirectUri)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<AuthorizationCode> { listener.awaitCallback(pending, 3000) }
                val reply = request(listener, callback(pending).rawPath + "?" + callback(pending).rawQuery)
                val code = result.get(3, TimeUnit.SECONDS)
                assertEquals("synthetic-code", code.code)
                assertEquals(TEST_CLIENT, code.clientId)
                assertTrue(reply.startsWith("HTTP/1.1 200"))
                assertTrue(reply.contains("finish verification"))
                assertTrue(reply.contains("Cache-Control: no-store"))
                assertFalse(reply.contains(code.code))
                assertFalse(reply.contains(pending.state))
                listener.close()
                // The listener's normal cleanup must not cancel the accepted exchange.
                expectFailure(SignInFailure.ATTEMPT_ALREADY_FINISHED) { OpenAiSignIn.parseCallback(callback(pending), pending) }
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `favicon wrong state bad Host duplicates and non-GET cannot terminate sign-in`() {
        LoopbackCallbackListener().use { listener ->
            val pending = OpenAiSignIn.createAttempt(TEST_HOST, listener.redirectUri)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<AuthorizationCode> { listener.awaitCallback(pending, 5000) }
                val valid = callback(pending).let { it.rawPath + "?" + it.rawQuery }
                val cases = listOf(
                    Triple("/favicon.ico", "127.0.0.1:${listener.redirectUri.port}", "GET"),
                    Triple(callback(pending, mapOf("state" to "wrong")).let { it.rawPath + "?" + it.rawQuery }, "127.0.0.1:${listener.redirectUri.port}", "GET"),
                    Triple(valid, "localhost:${listener.redirectUri.port}", "GET"),
                    Triple("$valid&state=wrong", "127.0.0.1:${listener.redirectUri.port}", "GET"),
                    Triple(valid, "127.0.0.1:${listener.redirectUri.port}", "POST")
                )
                for ((target, host, method) in cases) {
                    val response = request(listener, target, host, method)
                    assertFalse(response.startsWith("HTTP/1.1 200"))
                    assertFalse(response.contains(pending.state))
                    assertFalse(result.isDone)
                }
                val duplicateHost = request(listener, valid, extraHeaders = "Host: 127.0.0.1:${listener.redirectUri.port}\r\n")
                assertTrue(duplicateHost.startsWith("HTTP/1.1 400"))
                assertFalse(result.isDone)
                val bodyRequest = request(listener, valid, extraHeaders = "Content-Length: 2\r\n")
                assertTrue(bodyRequest.startsWith("HTTP/1.1 400"))
                assertFalse(result.isDone)
                request(listener, valid)
                assertEquals("synthetic-code", result.get(3, TimeUnit.SECONDS).code)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `verified OAuth denial terminates callback without echoing provider error`() {
        LoopbackCallbackListener().use { listener ->
            val pending = OpenAiSignIn.createAttempt(TEST_HOST, listener.redirectUri)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<AuthorizationCode> { listener.awaitCallback(pending, 3000) }
                val denied = callback(pending, mapOf("error" to "access_denied", "error_description" to "synthetic-secret"))
                val response = request(listener, "${denied.rawPath}?${denied.rawQuery}")
                assertTrue(response.startsWith("HTTP/1.1 400"))
                assertFalse(response.contains("synthetic-secret"))
                assertEquals(SignInFailure.AUTHORIZATION_DENIED, failed(result).reason)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `a partial HTTP header cannot hold the listener beyond its deadline`() {
        LoopbackCallbackListener().use { listener ->
            val pending = OpenAiSignIn.createAttempt(TEST_HOST, listener.redirectUri)
            val executor = Executors.newSingleThreadExecutor()
            val started = System.nanoTime()
            try {
                val result = executor.submit<AuthorizationCode> { listener.awaitCallback(pending, 150) }
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", listener.redirectUri.port), 1000)
                    socket.getOutputStream().write("GET ".toByteArray())
                    socket.getOutputStream().flush()
                    assertEquals(SignInFailure.CALLBACK_TIMED_OUT, failed(result).reason)
                }
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `close unblocks accept and cancelled attempts never return a code`() {
        LoopbackCallbackListener().use { listener ->
            val pending = OpenAiSignIn.createAttempt(TEST_HOST, listener.redirectUri)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<AuthorizationCode> { listener.awaitCallback(pending, 3000) }
                listener.close()
                assertEquals(SignInFailure.CANCELLED, failed(result).reason)
            } finally { executor.shutdownNow() }
        }
        LoopbackCallbackListener().use { listener ->
            val pending = OpenAiSignIn.createAttempt(TEST_HOST, listener.redirectUri)
            pending.cancel()
            expectFailure(SignInFailure.CANCELLED) { listener.awaitCallback(pending, 3000) }
        }
    }

    @Test fun `only the exact listener redirect and bounded wait are accepted`() {
        LoopbackCallbackListener().use { listener ->
            expectFailure(SignInFailure.INVALID_CONFIGURATION) { listener.awaitCallback(attempt(), 3000) }
            val pending = OpenAiSignIn.createAttempt(TEST_HOST, listener.redirectUri)
            expectFailure(SignInFailure.INVALID_CONFIGURATION) { listener.awaitCallback(pending, 300_001) }
            expectFailure(SignInFailure.CALLBACK_TIMED_OUT) { listener.awaitCallback(pending, 1) }
        }
    }

    private fun failed(result: Future<AuthorizationCode>): SignInException = try {
        result.get(3, TimeUnit.SECONDS)
        throw AssertionError("Expected callback failure")
    } catch (error: ExecutionException) {
        val failure = error.cause as? SignInException ?: throw error
        assertNull(failure.cause)
        failure
    }

    private fun request(
        listener: LoopbackCallbackListener,
        target: String,
        host: String = "127.0.0.1:${listener.redirectUri.port}",
        method: String = "GET",
        extraHeaders: String = ""
    ): String = Socket().use { socket ->
        socket.connect(InetSocketAddress("127.0.0.1", listener.redirectUri.port), 1000)
        socket.soTimeout = 2000
        socket.getOutputStream().apply {
            write("$method $target HTTP/1.1\r\nHost: $host\r\n${extraHeaders}\r\n".toByteArray(StandardCharsets.US_ASCII))
            flush()
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(512)
        while (true) {
            val count = socket.getInputStream().read(buffer)
            if (count < 0) break
            assertTrue(output.size() + count <= 4096)
            output.write(buffer, 0, count)
        }
        output.toString("UTF-8")
    }
}
