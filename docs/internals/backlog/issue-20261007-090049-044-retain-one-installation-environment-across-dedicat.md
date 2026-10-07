---
title: "Retain one installation environment across dedicated process preflight and install"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [enforcer]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/InstallationEnvironment.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/InstallationProgress.kt"
  - "enforcer/src/test/kotlin/io/mazewall/CapturedPlatformContextTest.kt"
  - "docs/internals/designs/enforcer/containment-design.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: HIGH]: Retain one installation environment across dedicated process preflight and install

**Context:**
C3: dedicated process preflight separately selects fallback/capabilities, then ordinary installation captures new state. A capability callback can replace the forbidden fallback before install. Introduce an immutable installation environment reusable by prepared runtime owners and forward it through the existing transaction.

**Needed:**
1. Reproduce fallback replacement during dedicated capability preflight and preserve the original required environment through installation.

## Implementation and verification evidence

`InstallationEnvironment` is one immutable selection of native backend, captured platform, fallback and self-verification gate. Per-attempt irreversible progress remains separate. Dedicated process preflight and installation use the same environment; the internal worker entry accepts explicit reuse by an owned prepared runtime without ambient overrides or a second compiler.

The dedicated capability callback regression failed when it replaced FAIL with SILENT_BYPASS between preflight and install (`run-55bca0a387b3`). An explicit-environment worker regression independently failed when worker entry recaptured globals instead of forwarding the environment (`run-2346e55479d7`). Both passed after forwarding; full host/profiler/ABI/quality checks passed (`run-e83cc7abe809`).

`./gradlew build integrationTest integrationTestFreshJvm` passed in 6m11s: 272 tasks (88 executed, 184 up-to-date), including dedicated required bootstrap (`run-f517b7541cef`). Serial caller-mapped full Docker gates passed in 59s (`run-0a250b9d43d5`). Environment lifetime and owned worker generations remain C7 work. The issue stays open for repository review.

---
<!-- id: issue-20261007-090049-044-retain-one-installation-environment-across-dedicat  file: issue-20261007-090049-044-retain-one-installation-environment-across-dedicat.md -->
