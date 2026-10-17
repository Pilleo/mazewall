---
title: "Diagnose executable mapping EPERM during enforcer worker completion"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [":enforcer"]
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/SandboxDispatcherCacheTest.kt"
target_symbols:
  - "SandboxDispatcher"
open_questions: false
---

# 🟡 [Severity: HIGH]: Diagnose executable mapping EPERM during enforcer worker completion

**Context:**
Local full follow-up verification run03620c0351aa aborted enforcer:test on GraalVM25.0.4.1: hs_err_pid88950.log records pool-2-thread-1, FutureTask.set -> VarHandleGuards -> native wrapper generation -> CodeHeap.expand_by, with executable mmap65536bytes denied EPERM. Prior runccabdcd7e413 similarly exited enforcer:integrationTest; isolated integration suite93e54971a1ca passed. Do not infer RAM exhaustion from the generic JVM message, treat unchanged retries as a fix, or weaken policies. Preserve fatal evidence and isolate which test/profile installed the restricting filter.

**Needed:**
1. Reproduce in a disposable JVM with exact worker/filter identity and distinguish strict NATIVE_IMAGE-on-HotSpot preconditions from the current admission-test-only change; retain denial and physical retirement guarantees.

## Runtime comparison

The same candidate's full build passes on installed Ubuntu OpenJDK25.0.4.1
(`run-b7e52d1e97d0`,357475ms) with the same tasks and no disabled assertions or
policy changes. This establishes a passing supported-runtime check, not a fix
or disproval of the retained GraalVM executable-mapping failure. The issue stays open.

The failure also occurred during an Ubuntu OpenJDK25 floor-class check
(`run-1ffce39ebb9f`, enforcer/hs_err_pid199455.log). The actual worker was
pool-2-thread-1 in native-wrapper/CodeHeap expansion; do not constrain the
investigation to GraalVM or treat the generic JVM out-of-memory text as RAM proof.
Five isolated floor child cases subsequently pass, but that is not a fix for
this independently retained fatal denial.

## Scoped library callback correction

The applyBpfFilter offset385 path is isolated to an unused unsupervised
AutoCloseable lambda created after filter installation. A fresh-child JFR ClassLoad
regression reproduces its late linkage; returning no lease removes the callback
without weakening executable-map denial. See
[scoped evidence](../../plans/2026-10-17-containment-post-install-bootstrap-evidence.md)
and issue-20261017-001100-102. Other retained FutureTask/VarHandle paths and the
documented strict-profile-on-HotSpot limitation remain under this broader issue;
one passing runtime/suite does not close it.

## Specific diagnostic path established

CI38822914139 on Temurin25.0.4.1 records pool-11-thread-1 in the actual
pkey_mprotect protection test. The genuine denial reaches Error.throwErrno;
cold string-concat invokedynamic resolves a native wrapper and commits65536
executable CodeHeap bytes on the restricted thread. The fatal report is retained
locally (`run-c8eeae4a78b4`). Scoped issue234007-259 removes that adapter bootstrap
without changing policies or denial assertions. It does not by itself establish
that every older FutureTask/VarHandle executable-mapping case is resolved; this
broader item remains open.

## HotSpot remediation worktree observation

Pre-commit `run-414d65775111` reproduced the fatal executable mmap denial on
GraalVM25.0.4.1.1 in ContainmentDesignSpec, pool-2-thread-1:
ContainedExecutors.applyBpfFilter -> MethodHandle native-wrapper generation ->
CodeHeap.expand_by (65536bytes, EPERM). The worktree retains
`enforcer/hs_err_pid2602181.log`; no enforcer changes were staged. The isolated
class passes on installed Ubuntu OpenJDK25 (`run-951d09818b2a`), as does the full
unmodified commit hook (`run-912f155266ca`). This is runtime-comparison evidence,
not a fix; the broader issue remains open.

## Direct retained-report follow-up

Both original reports were located read-only in `.worktrees/repository-cleanup/enforcer/`.
pid88950 is the genuine FutureTask.set/VarHandle completion path, after its callable
has returned. Its source callable is not visible; the current dispatcher worker
naming does not establish SandboxDispatcherCacheTest as that caller. pid199455
actually identifies ContainedExecutors.applyBpfFilter385/ContainmentDesignSpec and
is covered by the scoped callback correction, not a second FutureTask-set case.

The JVM-completion fixtures independently chose NATIVE_IMAGE despite promising
ordinary HotSpot work. An actual executable-map probe reproduces that mismatch;
explicit HOTSPOT_JIT fixture profiles and owned cache-pool cleanup correct it
without changing production policy. See
[worker profile evidence](../../plans/2026-10-17-jvm-worker-profile-evidence.md)
and issue-20261017-082745-923. This fixture correction does not invent provenance
for the original completion trace, so the broader investigation remains open.

## Full commit-gate FFM adapter observation

The operator-requested complete pre-commit build correctly rejects a real
GraalVM native integration failure (`run-2f8bff6c9276`). Retained
`enforcer/hs_err_pid3623280.log` identifies pool-11-thread-1 in
MmapProtectionTest.pkey_mprotect -> anonymousMapping -> RealNativeFileSystem.mmap
-> SyscallInvoker.mmap offset120 -> cold MethodHandle invocation linkage ->
native CodeHeap expansion65536bytes denied EPERM. This is before the intended
protection assertion, distinct from the previously fixed string-concat adapter
and no-op callback. The exact full log is preserved at
`build/reports/precommit-full-build/graal-native-failure.log` in the authorized
HotSpot worktree. No protection, assertion or build task was disabled to accept it.

The precise fixture setup is corrected under issue-20261017-092012-444: an unused
raw mmap handle is replaced with an actual owned non-executable allocation through
the typed production adapter before installing the strict filter. Focused actual
native protection checks pass; see
[typed mmap bootstrap evidence](../../plans/2026-10-17-typed-mmap-probe-bootstrap-evidence.md).
This does not change the native invocation/layout/errno contract or establish
arbitrary HotSpot safety under a no-executable-mapping policy.

## Owned native completion diagnostic decision (2026-10-17)

At implementation commit `49da8dcff79c2945e0a19199dde7ce6a61bfdb57`, the
owned disposable diagnostic completed both HOTSPOT_JIT and NATIVE_IMAGE profiles
on actual Ubuntu OpenJDK and GraalVM. All ten retained children reached
WORKER_RETIRED with exit 0 and no fatal reports. Independent unchanged native
MmapProtectionTest (17 tests) and BpfHardeningTest (15 tests) passed on each VM
with zero failures/errors/skips, preserving genuine EPERM assertions.

[The attributed evidence and raw SHA256 manifest](../../plans/2026-10-17-native-completion-attribution-evidence.md)
record the exact original report locations, VM/thread/stack facts, child identities,
journals/outcomes/VM/log hashes, retained XML and generated decision. They also
distinguish initial runs from final runs after the fixed-sleep synchronization
gate correction to timed Future.get polling.

The originals were inspected read-only: pid88950 is the FutureTask.set/VarHandle
completion path with **unknown source callable**; pid199455 is the distinct,
already-corrected applyBpfFilter+385 callback case. The new controls did not
reproduce the historical crash or attribute its callable. Their success does not
prove arbitrary JVM safety under strict executable-memory denial or authorize
weaker protection. The generated decision explicitly leaves callable attribution
and strict arbitrary-JVM safety unproved, with protection assertions unchanged.
**Keep this parent issue open at HIGH severity**, pending actual matching
callable/installing-policy evidence or an independently justified disposition of
that unknown; do not downgrade or archive it based on these passing controls.

---
<!-- id: issue-20261015-120935-494-diagnose-executable-mapping-eperm-during-enforcer-  file: issue-20261015-120935-494-diagnose-executable-mapping-eperm-during-enforcer-.md -->
