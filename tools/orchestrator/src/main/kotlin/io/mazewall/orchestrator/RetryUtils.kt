package io.mazewall.orchestrator

import java.util.concurrent.TimeUnit

object RetryUtils {
    fun <T> retry(
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000,
        env: OrchestratorEnvironment? = null,
        block: () -> T
    ): T {
        var lastException: Exception? = null
        var currentDelay = initialDelayMs

        for (attempt in 1..maxRetries) {
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    env?.errPrintln("⚠️ Attempt $attempt failed: ${e.message}. Retrying in ${currentDelay}ms...")
                    Thread.sleep(currentDelay)
                    currentDelay *= 2
                }
            }
        }
        throw lastException ?: IllegalStateException("Retry failed without exception")
    }
}
