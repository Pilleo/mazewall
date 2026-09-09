package io.mazewall.testing

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.LockSupport

fun awaitCondition(
    timeout: Duration = Duration.ofSeconds(5),
    description: String,
    predicate: () -> Boolean,
) {
    val timeoutNanos = deadlineNanos(timeout, description)
    val started = System.nanoTime()
    while (!predicate()) {
        if (Thread.interrupted()) throw InterruptedException("Interrupted waiting for $description")
        val remaining = timeoutNanos - (System.nanoTime() - started)
        if (remaining <= 0) throw AssertionError("Timed out after $timeout waiting for $description")
        LockSupport.parkNanos(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(1)))
    }
}

fun CountDownLatch.awaitOrFail(
    description: String,
    timeout: Duration = Duration.ofSeconds(5),
) {
    if (!await(deadlineNanos(timeout, description), TimeUnit.NANOSECONDS)) {
        throw AssertionError("Timed out after $timeout waiting for $description")
    }
}

fun <T> Future<T>.getOrFail(
    description: String,
    timeout: Duration = Duration.ofSeconds(5),
): T =
    try {
    get(deadlineNanos(timeout, description), TimeUnit.NANOSECONDS)
} catch (failure: TimeoutException) {
    throw AssertionError("Timed out after $timeout waiting for $description", failure)
}

private fun deadlineNanos(
    timeout: Duration,
    description: String,
): Long {
    require(description.isNotBlank()) { "Waiting requires a named condition" }
    require(!timeout.isNegative && !timeout.isZero) { "Waiting requires a positive timeout" }
    return timeout.toNanos()
}
