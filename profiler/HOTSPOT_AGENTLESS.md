# Explicit HotSpot agentless profiler

The public strategy `HOTSPOT_AGENTLESS` supervises the installed external
Gadget/OTel collector. Initial admission is Linux-amd64, exact pinned Temurin25
interpreter (`-Xint`), SerialGC and process-wide synchronous openat handler entry.
Attaching cannot change JVM startup settings. A normal running JIT JVM may be
refused before any workload code runs. AUTO continues selecting USER_NOTIF.

Build/install the [verified collector assets](hotspot-agentless/README.md)
explicitly. Library startup performs no build/download or automatic sudo. The
operator-configured argv prefix must preserve parent stdin/control/EOF and wait
for the actual collector. Parent JVM privilege probes do not admit/refuse an
independently authorized helper. Sessions own their collectors independently:
closing the selector prevents new work; finish/close every returned session.

## Attach a supported existing JVM

```kotlin
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.profiler.api.*
import java.nio.file.Path

MazewallProfiler.open(
    ProfileOptions(
        strategy = ProfileStrategy.HOTSPOT_AGENTLESS,
        processWide = true,
        captureStacks = true,
    ),
).use { profiler ->
    val options = HotSpotOptions(assetsDirectory = Path.of("/absolute/verified/assets"))
    profiler.attach(Pid(targetHostPid), options).use { session ->
        val partial = session.snapshot()
        // Run the target's operation window; keep the target alive through finalization.
        val final = session.finish()
        val evidence = requireNotNull(final.structuredEvidence)
        println(evidence.records.map { it.evidence.frames.map { frame -> frame.functionName } })
        println(evidence.summary.unavailable)
    }
}
```

`HotSpotOptions.collectorArgvPrefix` is an immutable operator argv vector, not a
shell command string. Defaults bound readiness to 10 seconds, session lifetime
to 60 seconds, finish to at most two remaining seconds, and Kotlin retained wire
payload to 4096 occurrences/32 MiB. Strict runtime admission cannot be disabled.
`processWide=true`, `captureStacks=true` and no recorded-log/io_uring override
are required. Missing/tampered/stale assets and unsupported options fail explicitly.

## Current-JVM workload window

```kotlin
val settings = ProfileOptions(strategy = ProfileStrategy.HOTSPOT_AGENTLESS, processWide = true)
val helper = HotSpotOptions(Path.of("/absolute/verified/assets"))
MazewallProfiler.open(settings, helper).use { profiler ->
    val result = profiler.profile { supportedWorkload() }
    println(result.value)
    println(result.structuredEvidence?.summary?.received)
}
```

Alternatively use `profiler.profile(helper) { ... }`. Attachment and validated
native ready precede the block. The observation scope includes background threads,
not just the lambda or a logical virtual-thread/asynchronous-parent task. Workload
and cleanup failures are both preserved through ordinary use/close suppression.

## Evidence, failure and policy eligibility

Typed evidence preserves occurrence identity, PID birth generation, BOOTTIME,
ordered immutable frames, optional BCI/source/native positions, uint64 provider
positions, unavailable/skipped outcomes, raw diagnostics and raw openat arguments.
Returned filename bytes are defensive copies. Relative/truncated paths are not
resolved Landlock operands, and handler entry is not a successful-operation result.
Snapshots are immutable and partial; pending pairs and retention loss are distinct.
Native physical drain, parent resource completion and whole-session collection
completeness are separate facts. Diagnostics are retained separately from JSONL,
with explicit discarded byte counts.

`HotSpotProfilingException` retains its original cause, optional partial result,
native errno observations, diagnostics and pending cleanup ownership. Explicit
`retryCleanup()` retries retained resources; it does not make failed collection
successful. Do not let a target exit/exec during metadata/enrichment drain.

Coverage is always incomplete in this version. Policy/DSL/dedicated-worker
conversion rejects it even after successful physical finalization. Result copies
preserve the typed evidence owner and cannot promote it using another coverage
record. Existing constructors and copy JVM descriptors remain intact.

`toProfileDocumentJson()` retains its existing behavior/coverage-only contract and
roundtrips the new strategy name. Save the independent diagnostic report with
`result.structuredEvidence!!.toJson()` and replay it with
`StructuredProfileEvidence.fromJson(json)`. That bounded report preserves frame
domains/optional values/raw fields and is not a policy or native-resource
attestation. Collection loss completeness remains false after deserialization.
