---
title: "BpfNativeCache Poisoned Across Engine Swaps: Mock Segments Reused by Real Engine"
severity: "HIGH"
status: "resolved"
priority: high
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/BpfNativeCache.kt"
  - "platform/src/main/kotlin/io/mazewall/LinuxNative.kt"
effort: "small"
autonomy: "autonomous"
open_questions: false
dependencies: []
---

# 🔴 [Severity: HIGH]: BpfNativeCache Poisoned Across Engine Swaps — Mock Segments Reused by Real Engine

**Context:** Discovered while adding `LandlockSubsetRealpathTest` (issue-20260823-135558).
`BpfNativeCache` cached `ManagedSegment`s in a global map keyed ONLY by the instruction list, but
segments are produced through the mockable `LinuxNative.memory.newSockFProg`. When a test installs a
policy under a mock engine and a later test installs a *byte-identical* program under the real
engine, the cached mock-produced segment (garbage bytes) is handed to `seccomp(2)`/`prctl(PR_SET_SECCOMP)`
— both fail intermittently with EINVAL depending on ordering/timing. Reproduced deterministically as
a ~50% failure rate running `ContainedExecutorWrapperTest` together with any mock-engine install test;
invisible in isolation.

**Needed (all done):**
1. Include `LinuxNative.engineIdentity` in the cache key (`NativeCacheKey(filters, engine)`), so
   segments are never shared across engine swaps. New platform accessor `LinuxNative.engineIdentity`
   exposes the active engine instance for this purpose.
2. Relocate `BpfNativeCache` from `io.mazewall.seccomp` to `io.mazewall`: reading
   `engineIdentity` from inside the restricted `io.mazewall.seccomp..` packages violates the ArchUnit
   NativeEngine-trait rule; the root package is outside that boundary.
3. Verified: pair-run flake eliminated (6/6 clean runs, previously 3/6 failing); full
   `./gradlew build` green including ArchitectureTest.

