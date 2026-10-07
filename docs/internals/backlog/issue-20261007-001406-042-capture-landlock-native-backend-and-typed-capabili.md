---
title: "Capture Landlock native backend and typed capabilities for ruleset lifetime"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["platform", "enforcer"]
target_files: 
  - "enforcer/src/main/kotlin/io/mazewall/landlock/LandlockState.kt"
  - "enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "platform/src/main/kotlin/io/mazewall/ffi/memory/NativeArena.kt"
  - "platform/src/main/kotlin/io/mazewall/platform/fd/FileDescriptor.kt"
  - "enforcer/src/test"
  - "enforcer/src/integrationTest"
  - "enforcer/README.md"
  - "docs/internals/designs/enforcer/containment-design.md"
target_symbols: 
  - "LandlockSession"
open_questions: false
---

# 🟡 [Severity: HIGH]: Capture Landlock native backend and typed capabilities for ruleset lifetime

**Context:**
C3: Landlock sessions re-read global backend/capabilities across creation, path opening, rule insertion and restriction. Backend replacement can redirect ruleset operations; typed rejected/probe-failed capabilities must not become unsupported ABI bypass.

**Needed:**
1. Reproduce replacement after session capture/ruleset creation/rule insertion; propagate captured native owner through ruleset and filesystem helpers and retain typed capability failures.

## Verification evidence

- Base revision `e4a55943`, inline C3 work. Six host regressions reproduced backend/capability replacement after session capture, ruleset creation, rule insertion and privilege setup, plus rejected/probe-failed capabilities being treated as unsupported ABI.
- Session captures native backend and typed capabilities once. Ruleset retains native owner; create/open/add-rule/restrict operations and ruleset/path FD cleanup consume it explicitly. Existing owned-token acquisition/epoch retirement remains intact; no invented descriptor token or ambient override is introduced.
- Optional captured filesystem arguments in existing descriptor close/use and path-open helpers preserve normal ownership checks and permit cleanup on the selected backend. Default callers still select a current snapshot; no public ABI changes were introduced.
- Capability permission rejection retains errno; probe failure retains original cause and failure state. Native ABI/right validation and failure handling retain captured fallback.
- Real-kernel isolated test creates a ruleset, replaces the global engine, completes restriction, observes actual file-open denial, confirms no foreign backend operation/cleanup, and leaves the caller readable. One native case passed, zero skips/errors/failures.
- Initial native test exposed post-restriction diagnostic ABI re-query through the replaced engine. The applied event now reports captured ABI metadata instead of issuing a new capability syscall after restriction. This fixes the graph rather than relaxing the test or grants.
- Full platform/enforcer/profiler host and ABI gates passed. Factored capability dispatch to satisfy the unchanged ReturnCount warning ratchet, without suppression/inventory growth.
- `./gradlew build integrationTest integrationTestFreshJvm` passed: 272 tasks, 106 executed, 166 up-to-date. Full caller-mapped Docker gate passed in 53s.
- Unified application-owned context, supervised graph/optional verification capture, and managed physical worker retirement remain C3/C7 work. This issue remains open for repository review.

---
<!-- id: issue-20261007-001406-042-capture-landlock-native-backend-and-typed-capabili  file: issue-20261007-001406-042-capture-landlock-native-backend-and-typed-capabili.md -->
