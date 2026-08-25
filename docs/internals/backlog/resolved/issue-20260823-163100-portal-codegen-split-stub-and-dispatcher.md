---
title: "Portal codegen writes stub and dispatcher into one output directory"
severity: "ENHANCEMENT"
status: "resolved"
priority: medium
dependencies:
  - "issue-20260823-121400"
  - "issue-20260823-121500"
component: "enforcer"
target_modules:
  - ":portal-codegen"
target_files:
  - "portal-codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalCodegenPlugin.kt"
  - "portal-codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalStubGenerator.kt"
effort: "medium"
autonomy: "supervised"
open_questions: false
---

# 🔵 [Severity: ENHANCEMENT]: Portal codegen writes stub and dispatcher into one output directory

**Context:** [process-portal-design.md](../../designs/enforcer/process-portal-design.md) says the plugin generates host stubs into `:portal` and worker dispatchers into `:portal-worker`. `GeneratePortalStubsTask` has a single `outputDir` and `PortalStubGenerator.write` dumps both `*PortalStub` and `*PortalDispatcher` there. Applying the plugin to the broker module would compile the dispatcher (and thus encourage loading guest dispatch) on the broker classpath.

**Needed:**
1. Two outputs: stub FileSpec → broker source set; dispatcher FileSpec → worker source set (or two tasks / two plugin applications with an explicit role).
2. Test that generated dispatcher source is not on the broker compile classpath of the plugin fixture.
3. Do not generate `Impl` classes in either output.
