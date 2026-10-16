---
title: "Classify transient unreadable descriptor observations in ForeignFdGuard"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "src/sharedTest/kotlin/io/mazewall/core/ForeignFdGuard.kt"
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

---
<!-- id: issue-20261009-014853-723-classify-transient-unreadable-descriptor-observati  file: issue-20261009-014853-723-classify-transient-unreadable-descriptor-observati.md -->
