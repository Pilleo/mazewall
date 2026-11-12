---
title: "Preserve prepared resource ownership on predispatch close exceptions"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/ffi/internal/PreparedPath.kt"
target_symbols:
  - "PreparedPath"
open_questions: false
---

# 🟡 [Severity: HIGH]: Preserve prepared resource ownership on predispatch close exceptions

**Context:**
D2 independent review follow-up source analysis finds PreparedPath finally marks a token closed on predispatch exceptions, while aggregate plan/operation/Landlock owners may latch closed; later D2 retry can label Released despite leaked original FD. Historical failure still prevents Complete. No runtime reproduction yet; source report resource-close-gap-001.md.

**Needed:**
1. Authorize minimal connected owner-scope extension, reproduce genuine acquired FD disposition in isolated tests, and preserve retryable ownership without re-closing any terminal native-close generation.

## Candidate verification

The original source finding was followed by a disposable-child predispatch RED and aggregate RED; `review-owner-connected-green-static-003` passed all four published-owner close controls. This does not establish recovery for pre-publication acquisition rollback (issue 20261106-013649-338).

Candidate gates in `b4-d2-implementation-001`: connected enforcer 268/268, platform 474/474 and profiler 32/32, with no skips or failures; static004 ratchet reported zero added warnings and no baseline growth. Rootful007 passed 8/8 and successor helper-regression-rootful001 passed 18/18; both postflight input manifests matched (1,829 checks each). Negative cases with injected effects are controlled tests, not proof of naturally occurring faults. Whole-B4 build002 was pending in the ledger; this issue remains open for controller settlement.

---
<!-- id: issue-20261105-223351-669-preserve-prepared-resource-ownership-on-predispatc  file: issue-20261105-223351-669-preserve-prepared-resource-ownership-on-predispatc.md -->
