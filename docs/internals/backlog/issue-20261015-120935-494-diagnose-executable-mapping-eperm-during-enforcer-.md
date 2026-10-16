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

## Specific diagnostic path established

CI38822914139 on Temurin25.0.4.1 records pool-11-thread-1 in the actual
pkey_mprotect protection test. The genuine denial reaches Error.throwErrno;
cold string-concat invokedynamic resolves a native wrapper and commits65536
executable CodeHeap bytes on the restricted thread. The fatal report is retained
locally (`run-c8eeae4a78b4`). Scoped issue234007-259 removes that adapter bootstrap
without changing policies or denial assertions. It does not by itself establish
that every older FutureTask/VarHandle executable-mapping case is resolved; this
broader item remains open.

---
<!-- id: issue-20261015-120935-494-diagnose-executable-mapping-eperm-during-enforcer-  file: issue-20261015-120935-494-diagnose-executable-mapping-eperm-during-enforcer-.md -->
