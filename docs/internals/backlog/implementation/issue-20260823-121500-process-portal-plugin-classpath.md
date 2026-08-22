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
open_questions: true
---

# 🔵 [Severity: ENHANCEMENT]: Process portal: guest implementation off the broker classpath

**Context:** Phase 1 allows a shared first-party classpath because guest code is still not *executed* in the broker. Phase 2 (untrusted plugins) requires guest classes to be absent from the broker JVM so a compromised broker classpath cannot load the plugin.

**Needed:**
1. Separate compile/runtime classpaths: broker sees interfaces + stubs only; worker sees implementations.
2. ArchUnit or a Gradle check that `PortalBuiltinDispatch` / `@SandboxImpl` classes are not on the broker test runtime for the plugin fixture.
3. Document the source-set layout in the process-portal design doc.

## ❓ Open Questions
1. Is a second Gradle source set (`workerMain`) required, or is a separate worker module enough for v1 plugins?
