---
title: "Replace Misleading Containment AutoCloseable with Installation Receipt"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/Policy.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSession.kt"
effort: "medium"
autonomy: "supervised"
paperclip_issue_id: 653c84e6-41a5-4319-b5b1-06a7a3862dfd
paperclip_identifier: MAZ-454
---

# 🟡 [Severity: MEDIUM]: Replace Misleading Containment AutoCloseable with Installation Receipt

**Context:** `Policy.install()` returns `AutoCloseable`, encouraging `use {}` and implying that closing restores the thread's previous permissions. Seccomp and Landlock are irreversible; only auxiliary supervisor/session resources can be closed. `ContainedExecutors.installOnCurrentThread` also exposes inconsistent `Unit` and internal `AutoCloseable` variants.

**Needed:** With breaking-change approval, return a non-closeable `InstallationReceipt` containing scope, requested/effective policy, installed kernel mechanisms and diagnostics. Give auxiliary closeable resources a distinct `SupervisorSession` lifecycle that never claims to undo containment. Add compile/KDoc examples that prohibit lexical “temporary sandbox” semantics and tests proving close operations do not alter the recorded or kernel-enforced policy.
