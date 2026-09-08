---
title: "Keep portal-worker Landlock process-scoped not thread-local"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "docs"
target_modules:
  - ":portal-worker"
target_files:
  - "portal-worker/src/main/kotlin/io/mazewall/portal/worker/PortalWorkerMain.kt"
target_symbols:
  - "PortalWorkerMain"
needs_kernel: false
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

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260907-081119  file: issue-20260907-081119-keep-portal-worker-landlock-process-scoped-not-thread-local.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
