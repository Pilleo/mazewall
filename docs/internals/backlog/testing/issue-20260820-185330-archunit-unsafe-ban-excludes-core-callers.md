---
title: "ArchUnit FileDescriptor.unsafe ban excludes io.mazewall.core callers"
severity: "LOW"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt"
effort: "small"
autonomy: "autonomous"
---

# 🟢 [Severity: LOW]: ArchUnit FileDescriptor.unsafe ban excludes io.mazewall.core callers

**Context:** `ArchitectureTest.fileDescriptorUnsafeMustNotBeUsedInProduction` ignores callers in `io.mazewall.core..` so the `FileDescriptor` factories themselves are allowed to exist. `SocketManager` also lives in `io.mazewall.core` and was just migrated off `unsafe()`. A regression that puts `FileDescriptor.unsafe()` back into `SocketManager` (or any other core production type) will not fail this rule.

The Companion/`unsafe$default` match in the same rule is now present; this is the remaining hole.

**Needed:**
1. Exclude only `FileDescriptor` / `FileDescriptor$Companion` as *call targets that may define* `unsafe`, not the entire `io.mazewall.core` package as callers.
2. Keep `DoNotIncludeTests`. Add a regression comment or a focused ArchUnit example so a core caller of `unsafe` fails the rule.
