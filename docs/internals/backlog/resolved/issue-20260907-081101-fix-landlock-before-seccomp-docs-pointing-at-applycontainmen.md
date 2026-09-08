---
title: "Fix Landlock-before-seccomp docs pointing at applyContainment"
severity: "LOW"
status: "resolved"
priority: low
dependencies: []
component: "docs"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/ContainedExecutorWrapper.kt"
  - "docs/internals/designs/enforcer/containment-design.md"
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "autonomous"
open_questions: false
has_side_effects: false

paperclip_issue_id: "d53d6374-5f46-48ff-bd8f-c41ec57bdcd8"
paperclip_identifier: "MAZ-1185"
---

# 🟢 [Severity: LOW]: Fix Landlock-before-seccomp docs pointing at applyContainment

**Context:**
Landlock-before-seccomp is documented on `ContainedExecutorWrapper.applyContainment()`, which does not exist. The real order is `installInternal`'s try-block (`applyLandlockIfNecessary` then `installSeccompFilter`). Wrapper wrap methods only call `installOnCurrentThread`. That stale name is a review trap.

**Needed:**
1. Point KDoc, `enforcer/AGENTS.md`, and `containment-design.md` at `installInternal` / `applyLandlockIfNecessary` then `installSeccompFilter`.
2. Remove references to `applyContainment()`.
3. No code behavior change.

## Side effects
- None

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260907-081101  file: issue-20260907-081101-fix-landlock-before-seccomp-docs-pointing-at-applycontainmen.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->

## Resolution evidence (2026-09-07)

- `enforcer/AGENTS.md`, `containment-design.md`, and `ContainedExecutors` KDoc all name `installInternal`, `applyLandlockIfNecessary`, and `installSeccompFilter` in the required order.
- A repository search found no stale `applyContainment()` reference.
