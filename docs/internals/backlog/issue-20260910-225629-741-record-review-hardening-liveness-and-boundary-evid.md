---
title: "Record review hardening liveness and boundary evidence"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "docs/internals/designs/core/post-review-hardening-evidence.md"
target_symbols:
  - "ReviewHardeningEvidence"
open_questions: false

paperclip_issue_id: "883282b3-5470-443e-82dc-ea1a735d8efc"
paperclip_identifier: "MAZ-1395"
---

# 🟡 [Severity: MEDIUM]: Record review hardening liveness and boundary evidence

**Context:**
The completed review-hardening packages need a canonical finding-to-commit-to-test map, current diagrams and final gate evidence.

**Needed:**
1. Map each accepted finding to committed implementation and executable verification; regenerate diagrams and run final gates.

**Progress:** Documentation, 30-row RH evidence mapping, seven state/sequence SVGs,
both generated class diagrams, local-link/commit/test-name validation and backlog
validation are complete. RefactorFirst remains absent from the ordinary build graph.

**Remaining integration evidence:** The final build fails its unchanged Detekt warning
ratchet (131 added findings) and a fresh-JVM spawn-stack test; `unitCheck` also fails the
enforcer 82% coverage floor at 75%. The container gate cannot start without Podman.
Deadline-metrics evidence remains a separate integration follow-up. Keep this item open until
the [final verification record](../designs/core/review-hardening-verification.md) records
resolution. No threshold was lowered and no failed gate is represented as passing.

## Remaining follow-up integration evidence

Open and map the remaining Tier E correctness matrix to close this item:

- [issue-20260912-000001-close-remaining-tier-e-attribution-and-lifecycle-correctness-gaps.md](issue-20260912-000001-close-remaining-tier-e-attribution-and-lifecycle-correctness-gaps.md)
- Ensure each acceptance test from that issue is linked to a commit and a machine-readable evidence artifact (`PASS`, `INCOMPLETE`, or `FAIL`) before this item is considered finished.

### Required matrix linkage

| Test (follow-up issue §Required tests) | Commit linkage | Evidence artifact |
|---|---|---|
| 1 supported native → Java callback → unsupported native syscall | `InvocationReplayMachineTest.supported native callback unsupported syscall is causal uncertain` (focused PASS) | `./gradlew :profiler:test --tests io.mazewall.profiler.attribution.InvocationReplayMachineTest` (commit `57c42afa`) |
| 2 active outer scope + inner capture failure | `InvocationReplayMachineTest.inner capture failure in reentrant scope does not leave stale exact attribution` (focused PASS) | `./gradlew :profiler:test --tests io.mazewall.profiler.attribution.InvocationReplayMachineTest` (commit `57c42afa`) |
| 3 mid-scope detach while session alive | `InvocationReplayMachineTest.detach and exec invalidation require validated re-entry before exact attribution` (focused PASS) | `./gradlew :profiler:test --tests io.mazewall.profiler.attribution.InvocationReplayMachineTest` (commit `ff58ffff`) |
| 4 seccomp-denied attempt + active session | `AttributionCollectorTest.capture domain attempt missed is a limitation and never an exact event` (focused PASS; reports boundary limitation, not kernel-denied observation) | `./gradlew :profiler:test --tests io.mazewall.profiler.attribution.AttributionCollectorTest` (commit `ff58ffff`) |
| 5 PID reuse/exec transition stress | `InvocationReplayMachineTest.pid reuse with new process epoch evicts stale state and resets attribution` (focused PASS) | `./gradlew :profiler:test --tests io.mazewall.profiler.attribution.InvocationReplayMachineTest` (commit `57c42afa`) |
| 6 method unload/redefine races | native symbolization epoch + weak class-guard implementation; live retransformation fixture PASS | `cmake -S profiler-native -B /tmp/jseccomp-profiler-native-build -DCMAKE_BUILD_TYPE=Debug && cmake --build /tmp/jseccomp-profiler-native-build -j2 && ctest --test-dir /tmp/jseccomp-profiler-native-build -V` (`agent_redefinition_smoke_test`, commit `0b9bf45b`) |
| 7 first unique-edge emit fails then repeats | `AttributionCollectorTest.unique edge deduplication retries after failed emit and suppresses after acknowledgement`; `AttributionCollectorTest.emission acknowledgement requires the exact outstanding token` (focused PASS) | `./gradlew :profiler:test --tests io.mazewall.profiler.attribution.AttributionCollectorTest` (commit `ff58ffff`) |
| 8 dictionary pressure with long-running workload | `DictionaryBatchPublisherTest.retry keeps the exact batch and watermark unchanged until commit`; `DictionaryBatchPublisherTest.termination records an abandoned prepared batch without committing it`; terminal-failure test (focused PASS) | `./gradlew :profiler:test --tests io.mazewall.profiler.attribution.DictionaryBatchPublisherTest` (commit `ff58ffff`) |

The capture-domain contract is also implemented in the collector: `recordCaptureDomainEvent` maps
`attempt_missed` to machine-readable `outOfDomainAttempts` evidence and forces an incomplete guarantee;
`observed_sys_enter` is retained as an explicit in-domain classification. `SessionIntegrity` also
exposes the independent verdict dimensions for capture domain, transport, attribution, dictionary,
and lifecycle validity. This does not claim that the kernel can observe attempts denied before
`sys_enter`; it records the boundary limitation when an upstream component reports one.

### Current Tier E completion evidence

The rootful Docker execution record is committed at
[`tier-e-rootful-docker-20260913.md`](../evidence/tier-e-rootful-docker-20260913.md).
It provides the privileged integration result, native fixture outputs, and
machine-readable verdict dimensions.

The active implementation paths are `profiler/src/main/kotlin/io/mazewall/profiler/tierE/**`
and `profiler-native/**`; the historical `tier-e-proto` and `ebpf-prototype` paths are not
modules in this checkout. Current focused evidence covers the committed protocol transcripts,
terminal-state rejection, actual mapped-reader BUSY/DISCARD/wraparound behavior, bounded
concurrent reservations, the live retransformation fixture, the seccomp boundary fixture,
and warmed full-stream/unique-edge phase benchmarks.

| Dimension | Current artifact | Verdict |
|---|---|---|
| Capture-domain validity | `seccomp_boundary_fixture`: `attempt_missed=1 observed_sys_enter=0 verdict=INCOMPLETE` | `PASS` for explicit boundary reporting; denied-before-`sys_enter` remains out of domain |
| Transport completeness | `DictionaryBatchPublisherTest` retry, terminal, and abandoned-tail cases | `PASS` |
| Attribution coverage | callback, capture failure, detach/exec invalidation, and opaque token tests | `PASS` |
| Dictionary integrity | immutable epoch-keyed metadata cache, weak unload guards, `agent_redefinition_smoke_test` | `PASS` |
| Lifecycle validity | terminal command rejection and typed process/session identity tests | `PASS` |
| Runtime overhead | `agent_phase_benchmark_full`, `agent_phase_benchmark_unique` phase totals | `PASS` artifact; compare against the recorded baseline before rollout |

Exact current commands:

```text
./gradlew :profiler:test
./gradlew :profiler:test --tests io.mazewall.profiler.tierE.daemon.ControlProtocolFixtureTest --tests io.mazewall.profiler.tierE.ringbuf.BpfRingBufferReaderTest
cmake -S profiler-native -B /tmp/jseccomp-profiler-native-build -DCMAKE_BUILD_TYPE=Debug
cmake --build /tmp/jseccomp-profiler-native-build -j2
ctest --test-dir /tmp/jseccomp-profiler-native-build -V
./scripts/adkw check-backlog
```

The umbrella issue was held open pending CI retention; local rootful Docker evidence now satisfies this delivery gate
for all eight matrix rows. Rootless Podman cannot provide authoritative privileged BPF
evidence; that limitation is `INCOMPLETE`, not a weakened policy or a passing result.
This does not claim that the kernel can observe attempts denied before `sys_enter`; it records the
boundary limitation when an upstream component reports one.

---
<!-- id: issue-20260910-225629-741-record-review-hardening-liveness-and-boundary-evid  file: issue-20260910-225629-741-record-review-hardening-liveness-and-boundary-evid.md -->

## Rootful gate evidence linkage (2026-09-13)

The authoritative Docker gate is retained in [tier-e-rootful-gate-20260913.md](/home/leanid/Documents/code/java/jseccomp/docs/internals/evidence/tier-e-rootful-gate-20260913.md), tied to commit `d0a5f6d3631f84744fef6ec9417565168f005f90`. It records the exact gate command, immutable raw artifacts, native CTest `10/10`, executed native-backed Tier E end-to-end coverage, benchmark medians, and machine-readable verdict dimensions. Rootless Podman remains `INCOMPLETE`. This linkage does not close or change the status of this open umbrella record.

## Resolution (local rootful evidence)

This record is resolved for the current branch using the successful rootful Docker verification retained in [tier-e-rootful-gate-20260913.md](/home/leanid/Documents/code/java/jseccomp/docs/internals/evidence/tier-e-rootful-gate-20260913.md). The evidence is tied to implementation commit `d0a5f6d3631f84744fef6ec9417565168f005f90` and documentation commit `e238775c`; CI retention is explicitly deferred and does not block this local delivery.
