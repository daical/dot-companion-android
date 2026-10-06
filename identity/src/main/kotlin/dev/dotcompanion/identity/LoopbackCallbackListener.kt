package dev.dotcompanion.identity

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Start this listener before opening authorization in the system browser. */
class LoopbackCallbackListener : AutoCloseable {
    private val server = try {
        ServerSocket().apply {
            reuseAddress = false
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8)
        }
    } catch (_: Exception) { fail(SignInFailure.CALLBACK_UNAVAILABLE) }
    private val closed = AtomicBoolean(false)
    private val awaiting = AtomicBoolean(false)
    private val activeSocket = AtomicReference<Socket?>()
    private val activeAttempt = AtomicReference<SignInAttempt?>()
    val redirectUri: URI = URI("http://127.0.0.1:${server.localPort}/auth/callback")

    fun awaitCallback(attempt: SignInAttempt, timeoutMillis: Long = 300_000): AuthorizationCode {
        if (attempt.redirectUri != redirectUri || timeoutMillis !in 1..300_000) fail(SignInFailure.INVALID_CONFIGURATION)
        if (!awaiting.compareAndSet(false, true)) fail(SignInFailure.CALLBACK_UNAVAILABLE)
        activeAttempt.set(attempt)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        var accepted = false
        try {
            attempt.assertFresh(System.currentTimeMillis() / 1000)
            while (true) {
                checkWaiting(attempt, deadline)
                server.soTimeout = minOf(250L, remainingMillis(deadline)).toInt().coerceAtLeast(1)
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                activeSocket.set(socket)
                try {
                    if (!socket.inetAddress.address.contentEquals(byteArrayOf(127, 0, 0, 1))) {
                        respond(socket, 403, false)
                        continue
                    }
                    val target = try { readRequest(socket, deadline) } catch (_: Exception) {
                        if (closed.get() || attempt.isCancelled) fail(SignInFailure.CANCELLED)
                        respond(socket, 400, false)
                        continue
                    }
                    val callback = try { URI("http://127.0.0.1:${redirectUri.port}$target") } catch (_: Exception) {
                        respond(socket, 400, false)
                        continue
                    }
                    if (callback.rawPath != "/auth/callback") {
                        respond(socket, 404, false)
                        continue
                    }
                    try {
                        val code = OpenAiSignIn.parseCallback(callback, attempt)
                        accepted = true
                        respond(socket, 200, true)
                        return code
                    } catch (failure: SignInException) {
                        respond(socket, 400, false)
                        if (failure.reason in setOf(SignInFailure.AUTHORIZATION_DENIED, SignInFailure.AUTHORIZATION_FAILED,
                                SignInFailure.CANCELLED, SignInFailure.EXPIRED_ATTEMPT, SignInFailure.ATTEMPT_ALREADY_FINISHED)) {
                            throw failure
                        }
                        // Bad state/path/duplicates must not terminate an otherwise valid attempt.
                    }
                } finally {
                    activeSocket.compareAndSet(socket, null)
                    try { socket.close() } catch (_: Exception) { }
                }
            }
        } catch (failure: SignInException) {
            throw failure
        } catch (_: Exception) {
            fail(if (closed.get() || attempt.isCancelled) SignInFailure.CANCELLED else SignInFailure.CALLBACK_UNAVAILABLE)
        } finally {
            if (!accepted) attempt.cancel()
            activeAttempt.compareAndSet(attempt, null)
            closeResources()
        }
    }

    override fun close() {
        // Closing while awaiting cancels it; closing after acceptance leaves code exchange usable.
        activeAttempt.get()?.cancel()
        closeResources()
    }

    private fun closeResources() {
        if (closed.compareAndSet(false, true)) {
            try { activeSocket.getAndSet(null)?.close() } catch (_: Exception) { }
            try { server.close() } catch (_: Exception) { }
        }
    }

    private fun checkWaiting(attempt: SignInAttempt, deadline: Long) {
        if (closed.get() || attempt.isCancelled) fail(SignInFailure.CANCELLED)
        if (System.nanoTime() >= deadline) fail(SignInFailure.CALLBACK_TIMED_OUT)
    }

    private fun readRequest(socket: Socket, overallDeadline: Long): String {
        val headerDeadline = minOf(overallDeadline, System.nanoTime() + 2_000_000_000L)
        val input = BufferedInputStream(socket.getInputStream(), 1024)
        val header = java.io.ByteArrayOutputStream()
        var ending = 0
        while (ending < 4) {
            val remaining = remainingMillis(headerDeadline)
            if (remaining <= 0 || header.size() >= 16_384) throw IllegalArgumentException("Invalid request")
            socket.soTimeout = minOf(500L, remaining).toInt().coerceAtLeast(1)
            val next = input.read()
            if (next < 0 || (next !in 32..126 && next !in setOf(9, 10, 13))) throw IllegalArgumentException("Invalid request")
            header.write(next)
            ending = when {
                ending == 0 && next == 13 -> 1
                ending == 1 && next == 10 -> 2
                ending == 2 && next == 13 -> 3
                ending == 3 && next == 10 -> 4
                next == 13 -> 1
                else -> 0
            }
        }
        val lines = String(header.toByteArray(), StandardCharsets.US_ASCII).removeSuffix("\r\n\r\n").split("\r\n")
        if (lines.size > 32 || lines.any { it.length > 8192 || it.any { c -> c.isISOControl() } }) {
            throw IllegalArgumentException("Invalid request")
        }
        val request = lines.first().split(' ')
        if (request.size != 3 || request[0] != "GET" || request[2] !in setOf("HTTP/1.1", "HTTP/1.0") ||
            !request[1].startsWith('/') || request[1].startsWith("//") || request[1].contains('#')) {
            throw IllegalArgumentException("Invalid request")
        }
        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (!line.contains(':') || line.startsWith(' ') || line.startsWith('\t')) throw IllegalArgumentException("Invalid request")
            val key = line.substringBefore(':').lowercase(Locale.ROOT)
            val value = line.substringAfter(':').trim()
            if (!key.matches(Regex("[a-z0-9-]+")) || headers.put(key, value) != null) throw IllegalArgumentException("Invalid request")
        }
        if (headers["host"] != "127.0.0.1:${redirectUri.port}" || headers.containsKey("transfer-encoding") ||
            (headers.containsKey("content-length") && headers["content-length"] != "0")) {
            throw IllegalArgumentException("Invalid request")
        }
        return request[1]
    }

    private fun respond(socket: Socket, status: Int, accepted: Boolean) {
        val message = if (accepted) "Sign-in received. Return to Dot Companion to finish verification."
            else "Sign-in could not be completed. Return to Dot Companion."
        val body = "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>Dot Companion</title><p>$message</p></html>"
            .toByteArray(StandardCharsets.UTF_8)
        val label = if (status == 200) "OK" else "Bad Request"
        val headers = "HTTP/1.1 $status $label\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n" +
            "Content-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\n" +
            "X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n\r\n"
        try {
            socket.getOutputStream().apply {
                write(headers.toByteArray(StandardCharsets.US_ASCII))
                write(body)
                flush()
            }
        } catch (_: Exception) { /* The verified callback remains valid if the browser closes. */ }
    }

    private fun remainingMillis(deadline: Long): Long = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
    override fun toString(): String = "LoopbackCallbackListener([redacted])"
}
