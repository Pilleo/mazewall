---
title: "Keep portal-worker Landlock process-scoped not thread-local"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "portal-worker"
target_modules:
  - ":portal-worker"
  - ":enforcer"
target_files:
  - "portal-worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerMain.kt"
target_symbols:
  - "PortalWorkerMain"
needs_kernel: true
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: true

paperclip_issue_id: "62aeb6e9-5b1c-458c-94a8-5aca55cfba30"
paperclip_identifier: "MAZ-1189"
---

# 🟡 [Severity: MEDIUM]: Keep portal-worker Landlock process-scoped not thread-local

**Context:**
Portal worker startup still applies thread-scoped Landlock via `installOnCurrentThread` (`PolicyScope.ThreadLocalOnly`) after process-wide install. `process-portal-design.md` documents that two-step; `portal/AGENTS.md` says keep portal behavior process-scoped and not import thread-local supervisor assumptions. Thread-scoped containment is not a complete ACE boundary.

**Needed:**
1. Remove `installOnCurrentThread` from portal-worker startup, or replace it with a documented process-wide restrict that matches `portal/AGENTS.md`.
2. Align `process-portal-design.md` with the chosen process-scoped behavior. Do not leave the two-step as "thread local after process".
3. Run portal-worker tests. Fail closed on Landlock apply errors.

## Side effects
- installOnCurrentThread is removed from portal-worker startup; Landlock remains process-wide

---

**Resolution (2026-09-10):** Worker startup now installs the combined filesystem, NO_EXEC, and NO_NETWORK baseline with `ContainedExecutors.installOnDedicatedProcess`. This internal entry requires Landlock TSYNC (ABI 8+), rejects fallback and virtual-thread installation, and invokes the existing process installation transaction so Landlock precedes Seccomp and partial irreversible stages retain their published state. The channel is owned before installation; guest construction and READY occur only afterwards. Unsupported older kernels fail startup.

**Verification:** `DedicatedProcessInstallationTest` covers unsupported TSYNC, fallback rejection, and virtual-thread rejection. `DedicatedProcessBaselineTest` launches a fresh child JVM, creates a sibling before installation, confirms that sibling cannot read `/etc/passwd`, and confirms a permitted JDK-file read succeeds. The isolated kernel test and all 18 `ProcessBrokerIntegrationTest` scenarios passed with the process-wide worker baseline. Worker startup tests cover ordering and failure cleanup. Backlog validated with `./scripts/adkw check-backlog`; no tools changes.

<!-- id: issue-20260907-081119  file: issue-20260907-081119-keep-portal-worker-landlock-process-scoped-not-thread-local.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
