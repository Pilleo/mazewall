---
title: "Preserve the void installation entry points for existing binaries"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3825290321
---

# 🔴 [Severity: HIGH]: Preserve the void installation entry points for existing binaries

**Review (2026-08-21):** ALREADY FIXED: hidden @Deprecated Unit overloads keep the JVM (...)V descriptor next to InstallationReceipt overloads.

**Context:** Changing `installOnCurrentThread` from a Kotlin `Unit` return to `InstallationReceipt` changes its JVM descriptor from `(...)V` to `(...)Lio/mazewall/InstallationReceipt;`. Applications compiled against the previous release will therefore fail with `NoSuchMethodError` after upgrading even though the new code is binary-compatible in behavior.

**Problem:**
- installOnCurrentThread changed from Unit to InstallationReceipt
- JVM descriptor changed from (...)V to (...)L...
- Existing binaries will fail with NoSuchMethodError

**Impact:**
- Breaking change for existing binaries
- NoSuchMethodError on upgrade

**Needed:**
1. Keep binary-compatible void entry points
2. Add new receipt-returning variants under distinct names
3. Or use @JvmName for compatibility

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825290321
