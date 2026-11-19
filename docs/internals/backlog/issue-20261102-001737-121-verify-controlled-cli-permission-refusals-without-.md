---
title: "Verify controlled CLI permission refusals without claiming authenticated denial"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: ["demos:cli-demo"]
target_files:
  - "demos/cli-demo/src/main/kotlin/demo/DemoApp.kt"
target_symbols:
  - "classifyContainmentFailure"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Verify controlled CLI permission refusals without claiming authenticated denial

**Context:**
Safe/all fail on expected JDK process and socket permission errors because inferred provenance is classified as infrastructure failure; existing tests permit the failing reports.

**Needed:**
1. Separate controlled operation refusal from authenticated denial, add positive controls and strict fresh-JVM report assertions, and preserve lifecycle failures.

**Evidence:**
- Original `DemoAppTest` regression: seven tests, two failures for process and network reports (`/tmp/cli-demo-fix-red.log`).
- Complete CLI module: 54 tests, zero failures/errors/skips (`/tmp/cli-demo-fix-module.log`). Includes operation-specific inferred-evidence, wrong-operation, fabricated text, missing executable, refused connection, timeout/interruption, cycle and cleanup regressions.
- Default and standalone safe modes exit zero in separate JVMs (`/tmp/cli-demo-fix-default.log`, `/tmp/cli-demo-fix-safe.log`). IntelliJ's original `demo.DemoAppKt.main()` launch also exits zero (`/tmp/cli-demo-fix-ide.log`).
- Observation surrounds only the target await after required setup and benign work; runtime drain is outside acceptance. Strict generic/profile evidence semantics remain unchanged.
- Read-only code review approved; its active-status placement finding was corrected before final module/CLI verification.
- Full repository build is running; scoped verification does not establish unrelated modules' quality.

---
<!-- id: issue-20261102-001737-121-verify-controlled-cli-permission-refusals-without-  file: issue-20261102-001737-121-verify-controlled-cli-permission-refusals-without-.md -->
