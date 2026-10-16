---
title: "Reduce and Version the Supported Public API Surface"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
  - ":profiler"
  - ":platform"
target_files:
  - "enforcer/build.gradle.kts"
  - "profiler/build.gradle.kts"
  - "platform/src/main/kotlin/io/mazewall/LinuxNative.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/Profiler.kt"
effort: "huge"
autonomy: "supervised"
open_questions: false
paperclip_issue_id: "e342490a-9637-4c22-934b-5f089dbeb2c5"
paperclip_identifier: "MAZ-1082"
---

# 🟡 [Severity: MEDIUM]: Reduce and Version the Supported Public API Surface

**Context:** The modules expose a large number of public declarations spanning application APIs, FFM arenas, native arguments, BPF builders, daemon engines, socket management and protocol state. This produces noisy discovery, allows unsupported coupling and creates a broad binary-compatibility burden.

**Needed:** Inventory and classify every public declaration as application API, supported SPI, unstable low-level API or internal implementation. With breaking-change approval, make implementation types internal or move them to an explicitly unstable artifact. Add generated API signature dumps and CI compatibility checks so new public declarations require intentional review. Keep the supported surface centered on Mazewall configuration, policies, contained executors, assessment/receipts, diagnostics, violations, profiler sessions/results and behavioral contracts.

**Architectural Decision:**
1. **Tooling:** Adopt `org.jetbrains.kotlinx.binary-compatibility-validator` (BCV) on `:enforcer`, `:profiler`, and `:platform` to track `.api` declarations and prevent unintentional surface expansion.
2. **Explicit API Mode:** Enable `explicitApi()` mode in Kotlin compilation tasks for production modules to force explicit visibility qualifiers (`public` vs `internal`), preventing accidental leakage of internal FFM wrappers or helper functions.

## Resolution evidence (2026-09-14)

Implemented the `0.1.0-SNAPSHOT` breaking baseline:

- documented the supported facade inventory and migration in `docs/public-api.md`;
- made native, FFM, descriptor, BPF, daemon, protocol, attribution, replay, and collector implementation contracts internal; retained application policy, containment, diagnostic, violation, profiler session/result, and behavioural-evidence types;
- changed public libraries to consume `:platform` as an implementation dependency and configured repository-only Kotlin friend access for direct implementation consumers and their tests;
- enabled strict `explicitApi()` for `:platform`, `:enforcer`, and `:profiler`;
- regenerated the approved Kotlin ABI baselines and added `verifySupportedApiSurface` to the normal `check` gate. The gate rejects forbidden implementation namespaces in the ABI snapshots.

Verification completed:

- `:platform:compileKotlin`, `:enforcer:compileKotlin`, and `:profiler:compileKotlin` under strict explicit API mode;
- all direct-consumer test source sets (`platform`, `enforcer`, `profiler`, `portal`, and `portal-worker`);
- focused runtime regressions for JUnit friend access, reflection compatibility, and architecture rules;
- `verifySupportedApiSurface` together with `:platform:checkKotlinAbi`, `:enforcer:checkKotlinAbi`, and `:profiler:checkKotlinAbi`.
