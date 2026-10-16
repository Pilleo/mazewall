package io.mazewall.portal

import java.time.Duration

/**
 * Host-owned resource contract for one generated portal policy profile.
 *
 * The worker keeps the fixed fail-closed Landlock/Seccomp baseline. Profiles
 * intentionally control only JVM heap, cgroup memory, and cgroup CPU limits.
 */
public data class PortalWorkerProfile(
    public val maxHeapBytes: Long? = null,
    public val memoryMaxBytes: Long? = null,
    public val cpuLimit: PortalCpuLimit? = null,
) {
    init {
        require((maxHeapBytes == null) == (memoryMaxBytes == null)) {
            "portal worker heap and memory limits must be configured together"
        }
        maxHeapBytes?.let { heap ->
            require(heap > 0) { "portal worker maxHeapBytes must be positive" }
            val memory = checkNotNull(memoryMaxBytes)
            require(memory > 0) { "portal worker memoryMaxBytes must be positive" }
            require(memory >= heap + MINIMUM_NON_HEAP_HEADROOM_BYTES) {
                "portal worker memoryMaxBytes must leave ${MINIMUM_NON_HEAP_HEADROOM_BYTES} bytes of non-heap headroom"
            }
        }
    }

    public val requiresCgroup: Boolean
        get() = memoryMaxBytes != null || cpuLimit != null

    internal fun jvmMaxHeapArgument(): String? = maxHeapBytes?.let { "-Xmx$it" }

    internal companion object {
        const val MINIMUM_NON_HEAP_HEADROOM_BYTES: Long = 32L * 1024L * 1024L
    }
}

/** cgroup v2 CPU quota expressed as Linux cpu.max quota and period values. */
public data class PortalCpuLimit(
    public val quota: Duration,
    public val period: Duration,
) {
    init {
        require(!quota.isZero && !quota.isNegative) { "portal worker CPU quota must be positive" }
        require(!period.isZero && !period.isNegative) { "portal worker CPU period must be positive" }
        require(quota.toNanos() % NANOS_PER_MICROSECOND == 0L) { "portal worker CPU quota must be whole microseconds" }
        require(period.toNanos() % NANOS_PER_MICROSECOND == 0L) { "portal worker CPU period must be whole microseconds" }
    }

    internal fun cpuMaxValue(): String = "${quota.toNanos() / NANOS_PER_MICROSECOND} ${period.toNanos() / NANOS_PER_MICROSECOND}"

    private companion object {
        const val NANOS_PER_MICROSECOND = 1_000L
    }
}

public class PortalWorkerProfileUnavailableException(
    profile: String,
) : PortalIsolationException("portal worker policy profile is not configured: $profile")

public class PortalCgroupUnavailableException(
    reason: String,
) : PortalIsolationException("portal worker cgroup v2 is unavailable: $reason")
