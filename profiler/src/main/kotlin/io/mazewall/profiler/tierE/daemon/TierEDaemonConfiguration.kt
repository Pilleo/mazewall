package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.internal.attribution.CoverageRecoveryMode
import io.mazewall.profiler.internal.attribution.MarkerVersion

internal fun markerVersionFromEnvironment(env: Map<String, String> = System.getenv()): MarkerVersion =
    when (env["TIER_E_MARKER_VERSION"]) {
        null, "", "v1" -> MarkerVersion.V1
        "v2" -> MarkerVersion.V2
        else -> error("TIER_E_MARKER_VERSION must be v1 or v2")
    }

internal fun receiptClockModeFromEnvironment(env: Map<String, String> = System.getenv()): String =
    when (env["TIER_E_COLLECTOR_RECEIPT_CLOCK"]) {
        null, "", "disabled" -> "disabled"
        "monotonic" -> "monotonic"
        else -> error("TIER_E_COLLECTOR_RECEIPT_CLOCK must be disabled or monotonic")
    }

internal fun coverageRecoveryModeFromEnvironment(env: Map<String, String> = System.getenv()): CoverageRecoveryMode =
    when (env["TIER_E_COVERAGE_RECOVERY_MODE"]) {
        null, "", "disabled" -> CoverageRecoveryMode.DISABLED
        "shadow" -> CoverageRecoveryMode.SHADOW
        "recover" -> CoverageRecoveryMode.RECOVER
        else -> error("TIER_E_COVERAGE_RECOVERY_MODE must be disabled, shadow, or recover")
    }

internal fun recoveryMapCapacityFromEnvironment(env: Map<String, String> = System.getenv()): Int =
    env["TIER_E_RECOVERY_MAP_CAPACITY"]?.toIntOrNull()?.takeIf { it in 1..CoverageRecoveryMode.DEFAULT_MAP_CAPACITY }
        ?: if (env.containsKey("TIER_E_RECOVERY_MAP_CAPACITY")) {
            error("TIER_E_RECOVERY_MAP_CAPACITY must be 1..4096")
        } else {
            CoverageRecoveryMode.DEFAULT_MAP_CAPACITY
        }

internal fun benchmarkDropRingSyscallFromEnvironment(env: Map<String, String> = System.getenv()): Int? =
    env["TIER_E_BENCHMARK_DROP_RING_SYSCALL"]?.toIntOrNull()?.takeIf { it >= 0 }
        ?: if (env.containsKey("TIER_E_BENCHMARK_DROP_RING_SYSCALL")) {
            error("TIER_E_BENCHMARK_DROP_RING_SYSCALL must be a nonnegative syscall number")
        } else {
            null
        }

internal fun nativeLiveSnapshotAdmissionFromEnvironment(env: Map<String, String> = System.getenv()): Boolean =
    when (env["TIER_E_LIVE_SNAPSHOT_ADMISSION"]) {
        null, "", "0" -> false
        "1" -> true
        else -> error("TIER_E_LIVE_SNAPSHOT_ADMISSION must be 0 or 1")
    }
