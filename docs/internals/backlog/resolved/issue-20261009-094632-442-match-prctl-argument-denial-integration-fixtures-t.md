---
title: "Match prctl argument-denial integration fixtures to their HotSpot substrate"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/PrctlBypassReproductionTest.kt"
target_symbols: 
  - "PrctlBypassReproductionTest"
open_questions: false
---

# ✅ [RESOLVED]: Match prctl argument-denial integration fixtures to their HotSpot substrate

**Context:**
Retained maze-scanner-11 full-build-1 integration worker174315 crashed executable CodeHeap65536 EPERM during LinuxNative errno string/error translation methodhandle/JNI linking in PDEATHSIG callback. Both prctl tests choose NATIVE_IMAGE while actuallyHotSpot. Assertions test only PDEATHSIG/ambient-raise EPERM and containment translation, not executable mmap denial; runtime selection changes only allowMmapExec, unsafePrctl remains false. Source/stack supports prctl EPERM already returned but no exact filter trace; unchanged retry notfix.

**Needed:**
1. Declare separate fixture/simulation scope, deterministic BPF behavioral RED for HotSpot mapping compatibility with preserved unsafeprctl EPERM/safeprctl allowance/strictNATIVE_IMAGE mapping-denial control, correct only explicit fixture profile, then exact native callback/result/translation proof and full gate without changing kernel floor/layout or assertions/skips/budgets.


## Closure evidence — Task 9 controller draft

**Proposed resolved, 2026-10-11. Tested integrated producer:
`dc6f7bf44b68f494a4cb609702dc97c72fb892c4`.** The fixture now selects the actual
HotSpot substrate without allowing unsafe prctl or weakening the strict Native
Image executable-mapping control. Task 2's meaningful simulator RED had **10
executed / 4 expected failures**; restored GREEN **10 PASS** and original native
callbacks **2 PASS**, no skips. Those private receipts retain their original
`d0ce1d9f`-plus-frozen-WIP labels, not the later integrated SHA.

The accepted Task 2 source/causal review, subsequent Task 3 prctl name/constant
correction and final Task 4 root custody preserve that contract. Current P's
once-run combined gate executes the matching simulator/native cases and all
applicable corrected suites; no historical crashing full build is relabeled PASS.
Receipts: this plan's `task-2-review.md`, accepted `task-3-fix2-review.md`,
`task-4-root-review.md`, `task-5-6-review.md`, `verification/prctl-{red,green,native}.*`,
`verification/combined.suite-acceptance.json` and frozen XML.
The separate failure-triage defect is addressed by its own accepted correction;
positive CET and installed/native opt-ins are not this fixture's closure proof.

---
<!-- id: issue-20261009-094632-442-match-prctl-argument-denial-integration-fixtures-t  file: issue-20261009-094632-442-match-prctl-argument-denial-integration-fixtures-t.md -->
