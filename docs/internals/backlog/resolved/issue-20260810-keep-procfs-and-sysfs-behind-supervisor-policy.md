---
title: "Keep procfs and sysfs behind supervisor policy"
severity: "HIGH"
status: "resolved"
priority: 10
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/BypassPaths.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/ResolveAbsolutePathTest.kt"
effort: "small"
autonomy: "autonomous"
---

# 🔴 [Severity: HIGH]: Keep procfs and sysfs behind supervisor policy

**Context:** The supervisor bypass list contained the `/proc` and `/sys` roots. Every supervised filesystem open beneath either virtual filesystem therefore received a seccomp continue response before stack-scoped policy evaluation, including sensitive process and kernel metadata.

**Needed:** Do not bypass either virtual filesystem wholesale. Any JVM runtime file that is empirically required to avoid coordination deadlocks must be identified and allowlisted as an exact path with dedicated regression coverage.

**Resolution:** Removed the `/proc` and `/sys` root bypasses and added regression coverage proving representative paths remain subject to supervisor policy evaluation.
