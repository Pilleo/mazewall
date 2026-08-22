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
open_questions: true
---

# 🟡 [Severity: MEDIUM]: Reduce and Version the Supported Public API Surface

**Context:** The modules expose a large number of public declarations spanning application APIs, FFM arenas, native arguments, BPF builders, daemon engines, socket management and protocol state. This produces noisy discovery, allows unsupported coupling and creates a broad binary-compatibility burden.

**Needed:** Inventory and classify every public declaration as application API, supported SPI, unstable low-level API or internal implementation. With breaking-change approval, make implementation types internal or move them to an explicitly unstable artifact. Add generated API signature dumps and CI compatibility checks so new public declarations require intentional review. Keep the supported surface centered on Mazewall configuration, policies, contained executors, assessment/receipts, diagnostics, violations, profiler sessions/results and behavioral contracts.

## ❓ Open Questions
1. **Binary Compatibility Tooling:** Should we introduce Kotlin Binary Compatibility Validator (`binary-compatibility-validator` Gradle plugin) or Metalava to enforce public API surface dumps on `:enforcer` and `:profiler`?
2. **Explicit API Mode:** Should Kotlin's `explicitApi()` compiler mode (`freeCompilerArgs += ["-Xexplicit-api=strict"]`) be enabled across all production modules to force explicit `public`/`internal` visibility keywords?

