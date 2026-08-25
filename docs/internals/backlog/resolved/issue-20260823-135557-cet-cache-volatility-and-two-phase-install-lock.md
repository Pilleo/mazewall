---
title: "Unsynchronized CET Cache Volatility and Two-Phase Landlock/Seccomp Install Lock"
severity: "MEDIUM"
status: "resolved"
priority: high
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/Platform.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
effort: "medium"
autonomy: "supervised"
open_questions: false
dependencies: []
---

# 🟠 [Severity: MEDIUM]: Unsynchronized CET Cache Volatility and Two-Phase Landlock/Seccomp Install Lock

**Context:** Two related state-management weaknesses:

1. **Non-volatile cache field.** `Platform.isCpuCetSupportedCached` (Platform.kt:195) is a plain `var`
   read/written from arbitrary threads (`isCpuCetSupported()` at lines 201-213 performs a check-then-set
   without synchronization), while its sibling fields `provider`, `cachedMatrix`, and
   `isCpuCetSupportedOverride` are all `@Volatile`. Concurrent first calls may probe twice (benign today),
   but `setProvider()`/`resetToDefault()` reset it non-atomically relative to readers — an inconsistent
   state hazard that will silently grow as more cached probes are added.
2. **Compound install is not atomic w.r.t. `processLock`.** `installInternal` applies Landlock inside
   `applyLandlockIfNecessary` under `processLock` (ContainedExecutors.kt:363), releases the lock, then
   reacquires it inside `installSeccompFilter` (line 316). The per-step ordering invariant
   ("Landlock strictly before Seccomp") holds within each step, but two concurrent thread-scoped installs
   can interleave as T1.landlock → T2.landlock → T2.seccomp → T1.seccomp, so registry state snapshots
   (`initialState`, fast-path reads at line 305 taken outside any lock) can diverge from kernel state
   during the window. Recovery logic in the catch block (lines 265-267) restores thread state based on
   this potentially stale snapshot.

**Resolution (2026-08-23):**
- Item 1 done earlier: `isCpuCetSupportedCached` is `@Volatile`.
- Item 2 resolved by investigation, no code change needed: `ContainmentStateRegistry.threadState`
  is a true ThreadLocal (`threadHolder by threadLocal { ... }`), so the gap between the Landlock
  and Seccomp critical sections cannot be corrupted by concurrent thread-scoped installs — other
  threads only ever touch their own ThreadLocal state. Process-wide installs serialize per phase on
  `processLock`, and since both Landlock and TSYNC seccomp are monotonic-restrictive with union
  semantics, phase interleaving between two process-wide installs is order-independent. The
  catch-block rollback snapshot stays valid for the same reason. Merging phases was evaluated and
  rejected (monitors are reentrant; worst-case hold time already bounded by the in-lock supervised
  handshake; daemon spawn deliberately outside the lock). The full concurrency model is now
  documented on `ContainedExecutors.installInternal`.
- Item 4 (concurrent-install stress test) folded into the differential-testing effort:
  issue-20260823-171500.

**Needed:**

**Needed:**
1. Mark `isCpuCetSupportedCached` as `@Volatile` (minimal fix), or route CET caching through the same
   double-checked pattern used by `featureMatrix`.
2. Evaluate making the whole install pipeline hold `processLock` across both phases (single critical
   section), or introduce a per-install transaction object so catch-block rollback uses the state observed
   under lock rather than a pre-lock snapshot.
3. Preserve deadlock-freedom: verify no callback invoked while holding `processLock` re-enters
   `ContainedExecutors`.
4. Add a stress test installing two distinct thread-scoped policies concurrently, asserting final
   `ContainmentStateRegistry` matches the union of installed filters after N iterations.

