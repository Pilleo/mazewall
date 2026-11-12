---
title: "Preserve native permission errno in confined helper peer authentication"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["enforcer"]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/ffi/networking/SupervisorHelperCredentials.kt"
target_symbols:
  - "SupervisorHelperCredentials"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Preserve native permission errno in confined helper peer authentication

**Context:**
D2 parent-peer-permission-red-001 compiles and proves SO_PEERCRED EPERM becomes Ancestry with null typed errno through getOrThrow then ConfinedHelperRejection.classify. Authentication still refuses and original text is retained, but finite permission classification is lost. Shared credential reader is outside the declared D2 file scope.

**Needed:**
1. Authorize the narrow shared credential-reader scope extension, preserve captured errno through helperValue, and verify EPERM/EACCES through real parent preparation before any SETUP or root transfer.

## Verification in progress

The operator approved the one-file extension. Original dirty bytes and SHA-256 are
preserved under `b4-d2-implementation-001/scope-extension-peer-001/`. The shared
reader now uses `helperValue`, retaining finite Permission with exact errno for
EPERM/EACCES. Parent-peer RED001 reproduced the misclassification; GREEN/static004
passed80/80 connected cases and owning lint/Detekt. Rootful006's controlled peer
EACCES prevented worker installation and physically retired the acquired helper.
Keep open until independent D2 review and the combined whole-B4 gate complete.

## Candidate verification

`parent-peer-permission-red-001` reproduced the lost typed errno; `parent-peer-green-static-004` passed 80/80. The rootful peer-permission negative uses a controlled native-effect decorator, not a naturally occurring permission denial.

Candidate gates in `b4-d2-implementation-001`: connected enforcer 268/268, platform 474/474 and profiler 32/32, with no skips or failures; static004 ratchet reported zero added warnings and no baseline growth. Rootful007 passed 8/8 and successor helper-regression-rootful001 passed 18/18; both postflight input manifests matched (1,829 checks each). Negative cases with injected effects are controlled tests, not proof of naturally occurring faults. Whole-B4 build002 was pending in the ledger; this issue remains open for controller settlement.

---
<!-- id: issue-20261105-202208-836-preserve-native-permission-errno-in-confined-helpe  file: issue-20261105-202208-836-preserve-native-permission-errno-in-confined-helpe.md -->
