---
title: "Preserve Tier E resource ownership until cleanup completes"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/TierEKotlinDaemon.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierECollectorRuntime.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierECollectorLoop.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/attribution/TierEProfilerSession.kt"
target_symbols:
  - "TierEDaemonService"
  - "TierECollectorRuntime"
  - "TierECollectorLoop"
  - "TierEProfilerSession"
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve Tier E resource ownership until cleanup completes

**Context:**
Static resource-safety review found activeSession cleared before retirement and finish failures suppressed; collector close marks ownership released before session cleanup succeeds.

**Needed:**
1. Add fault-injection coverage for failed finalization and retryable native cleanup across daemon, runtime, loop, and session owners.
2. Retain a closing owner until all resources are released; distinguish collection termination from successful resource retirement.
3. Attempt independent cleanup after failed finalization, preserve primary/suppressed failures and fatal identity, and reject replacement attachment while retirement remains incomplete.
4. Make repeated close retry only outstanding resources without repeating successful releases.

## Evidence and failure scenario

Static review on 2026-09-26; not yet runtime-reproduced:

- `TierEKotlinDaemon.kt:415–440`: `finishActiveSession` clears `activeSession` before terminal-watch closure/finalization and discards `runtime.finish()` failure with `runCatching(...).getOrNull()`.
- `TierECollectorLoop.kt:136–142`: `close()` sets `closed = true` before `session.close()`, preventing another close attempt after failure.
- `TierEProfilerSession.kt:919–929`: terminal detachment, quiescence, and counter reads precede source closure and can fail before it is reached.

A DETACH/SHUTDOWN or target-finalization failure can leave native descriptors and ring mappings live after their enclosing owner is forgotten. Reader-level retryable unmapping does not help if the enclosing loop refuses another close.

## Acceptance criteria and validation

- Fault injection covers interrupted collector join, marker detachment, terminal counter reads, and first-attempt unmap failure.
- Incomplete retirement is observable and retains reachable cleanup ownership; no replacement session bypasses it.
- Retry releases outstanding resources exactly once, preserves earlier failure evidence, and does not publish a stale successful terminal result.
- Extend the existing collector lifecycle and daemon tests; retain `BpfRingBufferReaderTest` failed-unmap retry coverage through the higher-level owners.
- Run focused profiler host tests, then `:profiler:test` and the merge gate. Run kernel verification if implementation changes native attachment/notification behavior.

## Related work and boundaries

This is a residual cleanup-ownership gap after [bounded Tier E lifecycle](resolved/issue-20260910-000105-bound-tier-e-retention-and-lifecycle.md), distinct from [collector integration and polling qualification](issue-20260918-210822-014-integrate-a-production-owned-tier-e-collector-loop.md). Preserve the producer-closure evidence contract and polling defaults. Read `../designs/core/notification-liveness-contract.md` before implementation. Declare affected test files in the task scope before execution.

---
<!-- id: issue-20260926-121446-405-preserve-tier-e-resource-ownership-until-cleanup-c  file: issue-20260926-121446-405-preserve-tier-e-resource-ownership-until-cleanup-c.md -->
