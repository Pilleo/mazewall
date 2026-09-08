---
title: "Replace orchestrator module identity String with sealed type"
severity: "LOW"
status: "open"
priority: medium
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/PathModules.kt"
target_symbols:
  - "PathModules"
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "supervised"
open_questions: false
has_side_effects: true

paperclip_issue_id: "f7db139a-fcf3-452f-8b8c-0f7cfaf893fa"
paperclip_identifier: "MAZ-1173"
---

# 🟢 [Severity: LOW]: Replace orchestrator module identity String with sealed type

**Context:**
`PathModules.componentFor` / `moduleFor` map path prefixes to module strings with `else -> "testing"` or `null`. A new Gradle module is silently classified as testing. That is a review and scheduler bug, not a sandbox bug.

**Needed:**
1. Introduce a sealed (or enum) module/component identity used by `PathModules`.
2. Replace string `else` fallbacks with an explicit `Unknown`/`Unsupported` variant or a compile-time list that must be extended when `settings.gradle.kts` gains a module.
3. Add a test that every directory that is a Gradle project in this repo maps to a known identity.
4. Run `./gradlew :tools:orchestrator:test --tests io.mazewall.orchestrator.PathModules*`.

## Side effects
- Unknown module strings become a compile error instead of mapping to testing

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260907-080940  file: issue-20260907-080940-replace-orchestrator-module-identity-string-with-sealed-type.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
