package io.mazewall.profiler.tierE

/**
 * A process-local bound for state retained after capture-side ownership has
 * transferred to the Tier E collector.
 *
 * The budget is deliberately independent of the individual queues. A batch,
 * token, cache entry, or copied observation consumes the same finite budget;
 * callers must release the exact amount they reserved.
 */
internal class TierERetainedStateBudget(
    val limitBytes: Long = DEFAULT_LIMIT_BYTES,
) {
    init {
        require(limitBytes > 0) { "limitBytes must be positive" }
    }

    private var usedBytes = 0L
    private val categoryBytes = linkedMapOf<Category, Long>()
    private var exhausted = false

    @Synchronized
    fun tryReserve(
        category: Category,
        bytes: Long,
    ): Boolean {
        require(bytes >= 0) { "bytes must not be negative" }
        if (bytes > limitBytes - usedBytes) {
            exhausted = true
            return false
        }
        usedBytes += bytes
        categoryBytes[category] = (categoryBytes[category] ?: 0L) + bytes
        return true
    }

    @Synchronized
    fun release(
        category: Category,
        bytes: Long,
    ) {
        require(bytes >= 0) { "bytes must not be negative" }
        val current = categoryBytes[category] ?: 0L
        require(bytes <= current) { "release exceeds reserved bytes for $category" }
        categoryBytes[category] = current - bytes
        usedBytes -= bytes
    }

    @Synchronized
    fun usedBytes(): Long = usedBytes

    @Synchronized
    fun remainingBytes(): Long = limitBytes - usedBytes

    @Synchronized
    fun bytesFor(category: Category): Long = categoryBytes[category] ?: 0L

    @Synchronized
    fun wasExhausted(): Boolean = exhausted

    internal enum class Category {
        DICTIONARY_STAGING,
        PENDING_OBSERVATIONS,
        PREPARED_BATCHES,
        EMISSION_TOKENS,
        SNAPSHOT_BUFFERS,
        METADATA_CACHE,
        RECOVERED_COVERAGE,
        RECOVERY_CANDIDATES,
        RECOVERY_SCAN_BUFFERS,
    }

    companion object {
        const val DEFAULT_LIMIT_BYTES = 64L * 1024L * 1024L
    }
}
