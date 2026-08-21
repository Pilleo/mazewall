---
title: "Require coverage evidence when generating DSL"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/BillOfBehavior.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3823789280
---

# 🔴 [Severity: HIGH]: Require coverage evidence when generating DSL

**Context:** When a USER_NOTIF or strace run has dropped or unresolved events but no recorded exec/connect destinations, the documented `result.behavior.toDsl()` path cannot receive `ProfilingResult.coverage`, so this method emits an apparently usable policy even though `result.toPolicy()` correctly rejects the same incomplete run.

**Problem:**
- toDsl() doesn't receive coverage parameter
- Incomplete runs can generate DSL
- toPolicy() rejects same incomplete run
- Inconsistent behavior between toDsl and toPolicy

**Impact:**
- Security: incomplete profiles can generate DSL
- Policy may be more permissive than intended

**Needed:**
1. Carry coverage through result-level DSL API
2. Reject !coverage.complete unless caller opts into incomplete
3. Make toDsl consistent with toPolicy

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3823789280
