---
title: "Classify transient unreadable descriptor observations in ForeignFdGuard"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "src/sharedTest/kotlin/io/mazewall/core/ForeignFdGuard.kt"
  - "src/sharedTest/kotlin/io/mazewall/core/ProcessDescriptorResources.kt"
  - "enforcer/src/test/kotlin/io/mazewall/platform/lifecycle/ForeignFdGuardTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/engine/ProfilerDaemonTest.kt"
target_symbols:
  - "ForeignFdGuard"
open_questions: false
---

# 🟡 [Severity: HIGH]: Classify transient unreadable descriptor observations in ForeignFdGuard

**Context:**
Unchanged quality commit hook failed SupervisorValidationChannelTest stalled validation peer afterEach: pre-test fd101 target unreadable later absent. Guard currentFds collapses every readSymbolicLink exception to unreadable, then treats the entry as an owned baseline foreign descriptor. Production/fixture foreign-close versus a transient procfs snapshot is not proven by this result. Preserve real foreign-FD protection and fail on real permission/inspection errors; do not disable/whitelist the guard to make tests pass.

**Needed:**
1. Reproduce snapshot disappearance separately from a real foreign descriptor close using owned fixtures, inspect exact readlink exception, and implement only evidence-backed classification while retaining actual foreign-close failures.

**Retained observations:**
Mandatory unchanged quality hook:1045 enforcer tests,1 failed,2 skipped. Failed
SupervisorValidationChannelTest `stalled validation peer is bounded by the installation
deadline`:ForeignFdGuard afterEach reported pre-testfd101 target`unreadable` vanished.
Exact failed XML retained in maze-scanner-5 artifact directory. One fresh guard-enabled
focused test1/0/0/0 and one unchanged full-hook retry passed. Neither proves a fix.
Guard currentFds collapses all readlink failures into`unreadable`; transient NoSuchFile
versus permission/real owner failure is unclassified. Keep actual foreign-close
detection mandatory; never disable the guard or whitelist a descriptor number.

**Reviewed observation-classification correction:**
Private53e85ef3058c19625d826c3fec92a615fe22f21f, integrated61ecc226f2e22ef57fda6e90e26de4350daa9282.
Independent ACCEPT, no Critical/Important findings. Successful fd/target retained;
only per-entry NoSuchFileException after validating root omits unverified entry;
every other inspection failure propagates unchanged. Verified BEFORE foreign close
still fails on absent/changed AFTER, including after-readlink disappearance. Public
JUnit noarg retained, observer exclusion/comparison frozen. Deterministic real-owner
RED5 tests/2 failures; final6 regression+6 original channel tests0skip GREEN.
Shared compile/ratchet GREEN; overall focused command FAILED on17/22 unchanged hard
Detekt errors, separately recorded in issue20261009-043520-499, not waived. Initial
hook interrupted at200s tool deadline had no verdict; one reconciled durable retry
passed133s. Originalfd101 cause remains unknown; XML proves absent OR changed target,
not absence alone. Final repository acceptance remains open.

## PR #573 follow-up

The JDK 25 [CI test report](https://github.com/Pilleo/mazewall/actions/runs/45881204597/artifacts/15148760284)
reported a changed or missing fd 0 pipe after the profiler's interrupted-session
setup test. Its mock syscall returned 0 for eventfd acquisition, and the daemon's
native filesystem backend dispatched a real close during startup rollback.

The profiler fixture now shares a mock backend for acquisition and close. Its
interruption test injects failure at listener flag setup and checks that the
socket and listener are each closed once. `ForeignFdGuard` uses a typed snapshot
and reports missing descriptors separately from changed targets. Both conditions
still fail the guard for verified foreign descriptors. The existing inspection
error propagation and stable-snapshot controls remain covered.

The new diagnostics and mock acquisition checks failed against the original
fixtures. On JDK 25, the exported PR #573 source with this follow-up passed all
77 targeted Platform, Enforcer, and Profiler tests plus lint for the changed
source sets. The full Profiler unit suite on the export reported 1,809 passed,
five skipped, and zero failures. The host Gradle build and confined retry
passed on JDK 25 in the shared checkout. Hosted verification was still pending
when that follow-up was recorded.

## PR #574 resolution

The observation-classification change is on the branch. JUnit now schedules
descriptor guards exclusively and lets concurrent child-process probes share
access across their test lifecycle. A guarded test can launch its own child
from a separate timeout thread. The Profiler daemon interruption fixture
keeps its simulated eventfd acquisition and retirement in the mock engine.

On JDK 25, the new launch regression reproduced the timeout with the old
thread-owned read/write lock and passed with scheduler coordination. The
guard suite also passed the real foreign-close, disappearing-readlink and
permission-failure checks. All 180 focused tests across the Platform,
Enforcer and Profiler suites passed, including 112 concurrent Enforcer
probe tests.

The repository-wide JDK 25 `build` passed on the baseline kernel lane in
21 minutes and 4 seconds, with the legacy CET exploit-demo class excluded.

---
<!-- id: issue-20261009-014853-723-classify-transient-unreadable-descriptor-observati  file: issue-20261009-014853-723-classify-transient-unreadable-descriptor-observati.md -->
