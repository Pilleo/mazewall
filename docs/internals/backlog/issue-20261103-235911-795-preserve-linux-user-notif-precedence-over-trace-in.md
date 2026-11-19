---
title: "Preserve Linux USER_NOTIF precedence over TRACE in policy intersection"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [":platform", ":enforcer"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/syscall/SeccompAction.kt"
target_symbols:
  - "restrictivenessRank"
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve Linux USER_NOTIF precedence over TRACE in policy intersection

**Context:**
Repository-wide review, 2026-11-04: [PLAT-08](../reviews/2026-11-04-repository-review-findings.md#plat-08). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P1 · policy composition/security correctness · reproduced action-selection defect + confirmed connected-source consequence · high confidence · core policy intersections containing TRACE and USER_NOTIF.**

- **Evidence:** `platform/src/main/kotlin/io/mazewall/platform/syscall/SeccompAction.kt:21–34` ranks TRACE at 40 and NOTIFY at 30. `enforcer/src/main/kotlin/io/mazewall/enforcer/api/PolicyDefinition.kt:76–89` applies `stricter` to both combined defaults and effective syscall actions; `55–65` also uses it when syscall numbers collide. Linux [seccomp.h:30–46](https://github.com/torvalds/linux/blob/v6.12/include/uapi/linux/seccomp.h#L30-L46) explicitly orders action words by signed minimum: USER_NOTIF (`0x7fc00000`) precedes TRACE (`0x7ff00000`). The library's ordering is reversed for this pair.
- **Trigger/effect:** intersect two policies whose effective action for one ordinary syscall is NOTIFY versus TRACE. Both argument orders select TRACE and eliminate that syscall's notification decision point from the combined policy. Kernel-stacked filters would select USER_NOTIF. With a ptracer enabled, TRACE can continue or rewrite the syscall; without one it yields ENOSYS ([kernel/seccomp.c:1255–1300](https://github.com/torvalds/linux/blob/v6.12/kernel/seccomp.c#L1255-L1300)). This is an incorrect intersection and loss of requested supervision, not a demonstrated bypass of independent filters or other containment layers.
- **Check run:** freshly compiled current `SeccompAction.kt` with `01-platform-pure-repro.kt`; both `ACT_NOTIFY.stricter(ACT_TRACE(7))` and the reverse return TRACE. The UAPI constants themselves are correctly encoded. No filter installation or ptrace experiment was performed.
- **Smallest correction:** make the shared total rank match Linux's full action order, retaining the existing first-policy tie/payload rule. Correct the comments claiming the action bits have no restrictiveness order: the signed, action-masked word is ordered; an unsigned comparison or comparison of payload-bearing return words is not the same rule.
- **Regression:** independently enumerate the UAPI action order and test every pair in both argument orders, including KILL_PROCESS's sign bit and same-action payload ties. Verify NOTIFY/TRACE combined defaults and explicit-versus-default policy actions through `PolicyDefinition.combine`/`intersection`. `platform/src/test/kotlin/io/mazewall/core/SeccompActionTest.kt:69–81,98` currently prescribes the wrong ranks and TRACE winner, so a passing existing suite will not detect this defect.
- **Tracking:** **expanded precedence gap** adjacent to resolved `docs/internals/backlog/resolved/issue-20260821-113004-give-errno-precedence-over-trace.md` and its duplicate `issue-20260820-231247-errno-trace-precedence.md`. ERRNO-over-TRACE is fixed; this distinct NOTIFY-over-TRACE pair remains wrong. Exact TRACE/NOTIFY/USER_NOTIF and rank searches found no existing issue or current plan for this pair. Central consolidation should scope the additional pair rather than re-report the repaired ERRNO case.
- **Dependency:** correct the platform order before accepting downstream policy-intersection guarantees; coordinate the enforcer regression with the BPF/policy lane. Independent of the recent action-payload validation fix.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235911-795-preserve-linux-user-notif-precedence-over-trace-in  file: issue-20261103-235911-795-preserve-linux-user-notif-precedence-over-trace-in.md -->
