---
title: "Prelink mmap protection fixture lambdas before executable mapping denial"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "enforcer/src/integrationTest/kotlin/io/mazewall/seccomp/MmapProtectionTest.kt"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Prelink mmap protection fixture lambdas before executable mapping denial

**Context:**
**Resolution — 2026-10-12:** Needed step1 and second-cold-linkage follow-up fulfilled: prelinked fixture lambdas and explicit StringBuilder denial message preserve actual unchanged Native Image policy/EPERM/raw return/cause/cleanup checks. E=`/home/leanid/Documents/code/java/jseccomp/.superpowers/sdd/2026-10-11-integration-contract-completion/`: `task-6b/recovery/task-6b-recovery-review.md`, `task-6b/checkpoint/checkpoint-fix-review.md` (all17 Mmap cases PASS; owning193 PASS/2 original CET skips), accepted c049 then P617 complete gates. [Acceptance](../../reviews/2026-10-12-integration-contract-completion.md). Historical crashes stay NO_PASS; prior open prose is historical.

Task6b actual combined gate exited 1 when a HotSpot test worker crashed linking an invokedynamic native wrapper after the NATIVE_IMAGE policy denied executable mmap. hs_err_pid719585.log names the pkey_mprotect test's cold inner mapping lambda, CodeHeap expansion, and failed 65536-byte executable commit. This is fixture placement, not host memory exhaustion or a permission waiver.

**Needed:**
1. Construct the fixture operation, mapping body and result supplier lambdas before installing the unchanged Native Image policy; retain actual syscall EPERM/raw return/cause identity and owned mapping cleanup checks. Verify the whole MmapProtectionTest and complete combined build, then obtain scoped fix review before final acceptance.

**Task6b checkpoint follow-up (12 October 2026):**
The fresh combined gate at the independently accepted recovery freeze exited 1 again in `:enforcer:integrationTest`. Its `hs_err_pid832786.log` identifies a different cold linkage site: `StringConcatFactory.makeConcatWithConstants` inside `strictNativeReturn`, reached by the real `pkey_mprotect` denial. CodeHeap expansion received EPERM while committing 65,536 executable bytes. The earlier lambda prelinking remains intact. Replace only this fixture's denial-message interpolation with explicit `StringBuilder` appends, retaining the exact diagnostic values and all native-result, cause, assertion, policy and cleanup contracts. Cover the unchanged whole-class controls and owning integration suite; controller review and a complete passing combined gate remain mandatory before checkpoint acceptance. Receipts live under `.superpowers/sdd/2026-10-11-integration-contract-completion/task-6b/checkpoint/` in the operator evidence workspace. This issue remains open until final acceptance.

---
<!-- id: issue-20261012-110955-698-prelink-mmap-protection-fixture-lambdas-before-exe  file: issue-20261012-110955-698-prelink-mmap-protection-fixture-lambdas-before-exe.md -->
