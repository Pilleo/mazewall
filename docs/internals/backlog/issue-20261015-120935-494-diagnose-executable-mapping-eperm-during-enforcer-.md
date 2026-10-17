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

---
<!-- id: issue-20261015-120935-494-diagnose-executable-mapping-eperm-during-enforcer-  file: issue-20261015-120935-494-diagnose-executable-mapping-eperm-during-enforcer-.md -->
