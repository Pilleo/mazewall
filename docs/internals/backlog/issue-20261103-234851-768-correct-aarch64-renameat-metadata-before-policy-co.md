---
title: "Correct AArch64 renameat metadata before policy compilation"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [":platform", ":enforcer"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/syscall/Arch.kt"
target_symbols:
  - "Arch"
open_questions: false
---

# 🟡 [Severity: HIGH]: Correct AArch64 renameat metadata before policy compilation

**Context:**
Repository review PLAT-01: AArch64 renameat is -1 although Linux assigns syscall 38. PolicyDefinition omits negative mappings, so an explicit RENAMEAT deny in a default-ALLOW policy disappears. Fresh current-source metadata reproduction and all-224-entry Linux v6.12 table audit confirm the mismatch; no AArch64 kernel run was performed. See docs/internals/reviews/2026-11-04-repository-review-findings.md#plat-01. This is a new scoped correction, not reopening the repaired recvmmsg/statx mappings.

**Needed:**
1. Correct the shared architecture entry and add independently sourced mapping plus compiled AArch64 default-ALLOW/explicit-deny and default-DENY/explicit-allow verdict regressions; retain the AMD64 control and qualify an isolated AArch64 native verdict before claiming that deployment supported.

---
<!-- id: issue-20261103-234851-768-correct-aarch64-renameat-metadata-before-policy-co  file: issue-20261103-234851-768-correct-aarch64-renameat-metadata-before-policy-co.md -->
