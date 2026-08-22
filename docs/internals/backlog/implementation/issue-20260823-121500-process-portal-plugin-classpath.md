---
title: "Process portal: guest implementation off the broker classpath"
severity: "ENHANCEMENT"
status: "open"
priority: low
dependencies:
  - "issue-20260823-121400"
component: "enforcer"
target_modules:
  - ":portal"
  - ":portal-codegen"
target_files:
  - "docs/internals/designs/enforcer/process-portal-design.md"
effort: "large"
autonomy: "supervised"
open_questions: false
---

# 🔵 [Severity: ENHANCEMENT]: Process portal: guest implementation off the broker classpath

**Context:** Phase 1 allows a shared first-party classpath because guest code is still not *executed* in the broker. Phase 2 (untrusted plugins) requires guest classes to be absent from the broker JVM so a compromised broker classpath cannot load the plugin.

**Needed:**
1. Separate compile/runtime classpaths: broker sees interfaces + stubs only; worker sees implementations.
2. ArchUnit or a Gradle check that `PortalBuiltinDispatch` / `@SandboxImpl` classes are not on the broker test runtime for the plugin fixture.
3. Document the source-set layout in the process-portal design doc.

## ✅ Resolution
Decision documented in [process-portal-design.md](../../designs/enforcer/process-portal-design.md) § "Phase 2: Plugin Classpath Layout": **Use a separate Gradle module (`:portal-worker`)** rather than a source set. This provides cleaner isolation: the broker depends only on `:portal` (stubs + interfaces), while workers are spawned with `:portal-worker` + plugin modules on their classpath. Gradle's dependency resolution guarantees the broker never loads worker classes.

## Remaining Tasks
1. Create `:portal-worker` module with guest implementations and worker dispatcher.
2. Implement Gradle check or ArchUnit test to verify `PortalBuiltinDispatch` / `@SandboxImpl` classes are not on the broker runtime classpath.
