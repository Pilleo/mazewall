---
title: "Process portal: KotlinPoet host stub and worker dispatcher"
severity: "ENHANCEMENT"
status: "resolved"
priority: medium
dependencies:
  - "issue-20260823-121200"
component: "enforcer"
target_modules:
  - ":portal-codegen"
  - ":portal"
target_files:
  - "portal-codegen/build.gradle.kts"
  - "docs/internals/designs/enforcer/process-portal-design.md"
effort: "large"
autonomy: "supervised"
open_questions: false
---

# 🔵 [Severity: ENHANCEMENT]: Process portal: KotlinPoet host stub and worker dispatcher

**Context:** The hand-written `ProcessBroker.echo` / `checksum` path exists so the Unix protocol can be tested without codegen. Production DX is a marker interface + generated broker stub + worker dispatcher (Glassbox-shaped, process isolation, no host `Impl()` fallback). KotlinPoet is a **plugin-only** dependency; it must not land on `:enforcer` or `:portal` runtime.

**Needed:**
1. New `:portal-codegen` Gradle plugin module. Generate host stub that serializes allowed types and attaches `Capability.ReadFd` via `SCM_RIGHTS`. Generate worker dispatcher that never runs in the broker.
2. Fail closed at `create()` if the stub class is missing. Never instantiate the guest implementation in the broker.
3. Boundary-type check at generate time: primitives, `String`, records/POJOs, `byte[]`, `Capability.ReadFd` only.
4. Keep a hand-written stub test so protocol bugs are not hidden in generated code.
5. Add `:portal-codegen` to `settings.gradle.kts` and `BacklogValidator.VALID_GRADLE_MODULES` if not already present.

## ❓ Open Questions
1. Approve adding KotlinPoet as a dependency of `:portal-codegen` only (not `:enforcer`, not `:portal` runtime)?
   **Resolved:** yes, plugin-only.
