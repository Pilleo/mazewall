---
title: "Reduce and Version the Supported Public API Surface"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
  - ":profiler"
target_files:
  - "enforcer/build.gradle.kts"
  - "profiler/build.gradle.kts"
  - "enforcer/src/main/kotlin/io/mazewall/LinuxNative.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/Profiler.kt"
effort: "huge"
autonomy: "supervised"
open_questions: false
paperclip_issue_id: 0cdcd683-c03d-41d2-ab47-b8e052ee09eb
---

# 🟡 [Severity: MEDIUM]: Reduce and Version the Supported Public API Surface

**Context:** The modules expose a large number of public declarations spanning application APIs, FFM arenas, native arguments, BPF builders, daemon engines, socket management and protocol state. This produces noisy discovery, allows unsupported coupling and creates a broad binary-compatibility burden.

**Needed:** Inventory and classify every public declaration as application API, supported SPI, unstable low-level API or internal implementation. With breaking-change approval, make implementation types internal or move them to an explicitly unstable artifact. Add generated API signature dumps and CI compatibility checks so new public declarations require intentional review. Keep the supported surface centered on Mazewall configuration, policies, contained executors, assessment/receipts, diagnostics, violations, profiler sessions/results and behavioral contracts.

**Architectural Decision:**
1. **Tooling:** Adopt `org.jetbrains.kotlinx.binary-compatibility-validator` (BCV) on `:enforcer`, `:profiler`, and `:platform` to track `.api` declarations and prevent unintentional surface expansion.
2. **Explicit API Mode:** Enable `explicitApi()` mode in Kotlin compilation tasks for production modules to force explicit visibility qualifiers (`public` vs `internal`), preventing accidental leakage of internal FFM wrappers or helper functions.


