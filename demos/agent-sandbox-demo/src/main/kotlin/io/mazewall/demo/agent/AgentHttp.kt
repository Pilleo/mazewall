package io.mazewall.demo.agent

import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpTimeoutException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Synchronous HTTP I/O stays on the invoking worker; the observer only expires header acquisition. */
internal object AgentHttp {
    private enum class ResponsePhase { WAITING, RECEIVED, EXPIRED }

    fun fetch(url: String): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 2000
        // The former HttpRequest timer ends at response headers, not during subsequent body reads.
        connection.readTimeout = 0
        connection.instanceFollowRedirects = false
        val phase = AtomicReference(ResponsePhase.WAITING)
        val response = CountDownLatch(1)
        val observer = Thread.ofPlatform().name("agent-http-response-deadline").inheritInheritableThreadLocals(false).start {
            if (!response.await(maxOf(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) && phase.compareAndSet(ResponsePhase.WAITING, ResponsePhase.EXPIRED)) {
                connection.disconnect()
            }
        }
        return try {
            val status = connection.responseCode
            if (System.nanoTime() >= deadline) phase.compareAndSet(ResponsePhase.WAITING, ResponsePhase.EXPIRED)
            if (!phase.compareAndSet(ResponsePhase.WAITING, ResponsePhase.RECEIVED)) throw HttpTimeoutException("HTTP response headers exceeded two seconds")
            response.countDown()
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            stream?.use { String(it.readBytes(), responseCharset(connection.contentType)) } ?: ""
        } catch (failure: Exception) {
            if (phase.get() == ResponsePhase.EXPIRED && failure !is HttpTimeoutException) {
                throw HttpTimeoutException("HTTP response headers exceeded two seconds").apply { initCause(failure) }
            }
            throw failure
        } finally {
            response.countDown()
            var interrupted = Thread.interrupted()
            while (observer.isAlive) {
                try {
                    observer.join()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            try {
                connection.disconnect()
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private fun responseCharset(contentType: String?): Charset {
        val name = Regex("(?:^|;)\\s*charset\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]*))", RegexOption.IGNORE_CASE)
            .find(contentType.orEmpty())?.let { it.groups[1]?.value ?: it.groups[2]?.value }
            ?: return StandardCharsets.UTF_8
        return try {
            Charset.forName(name)
        } catch (_: IllegalArgumentException) {
            StandardCharsets.UTF_8
        }
    }
}
