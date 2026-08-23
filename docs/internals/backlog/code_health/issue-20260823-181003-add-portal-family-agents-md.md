---
title: "Add nested AGENTS.md for portal, portal-codegen, and portal-worker"
severity: "LOW"
status: "open"
priority: medium
dependencies: []
component: "docs"
target_modules:
  - ":portal"
  - ":portal-codegen"
  - ":portal-worker"
target_files:
  - "portal/AGENTS.md"
  - "portal-codegen/AGENTS.md"
  - "portal-worker/AGENTS.md"
effort: "small"
autonomy: "autonomous"
open_questions: false
---

# 🟢 [Severity: LOW]: Add nested AGENTS.md for portal, portal-codegen, and portal-worker

**Context:**
The process-portal split (`docs/internals/designs/enforcer/process-portal-design.md`) is a distinct architecture from thread-scoped seccomp and from the syscall supervisor. Agents regularly confuse portal codegen, worker, and enforcer supervisor. There are no nested `AGENTS.md` files under `portal/`, `portal-codegen/`, or `portal-worker/`, so nearest-file loading never injects portal-specific constraints.

**Needed:**
1. Add a short `portal/AGENTS.md`: broker/worker split, capability FDs, “distinct from syscall supervisor”, pointer to `process-portal-design.md`, test command `./gradlew :portal:test`.
2. Add `portal-codegen/AGENTS.md`: generated stubs vs dispatcher boundaries; do not hand-edit generated output; tests under `portal-codegen/src/test`.
3. Add `portal-worker/AGENTS.md`: worker process isolation, what may run in the worker vs broker.
4. Keep each file under ~50 lines. Link, do not paste, the design doc.

---

**Verification:** Three files exist; no behavioral code changes; `./gradlew :portal:test :portal-codegen:test` still pass.
