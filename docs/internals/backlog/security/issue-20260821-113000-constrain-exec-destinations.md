---
title: "Constrain recorded exec destinations during policy compilation"
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
related_thread: 3819982841
---

# 🔴 [Severity: HIGH]: Constrain recorded exec destinations during policy compilation

**Context:** When a complete profile observes `execve("/usr/bin/tool", ...)`, `BobCompiler` records the destination in `execs`, but this new completeness gate checks only `connects`; the builder subsequently permits the observed `EXECVE` syscall without using or rejecting `execs`. The generated policy therefore permits execution of arbitrary destinations rather than the profiled binary.

**Problem:**
- execs are recorded but not enforced
- Completeness gate only checks connects
- Policy permits arbitrary exec destinations
- Bill of Behavior is widened

**Impact:**
- Security: policy is more permissive than observed behavior
- Exec destinations not enforced

**Needed:**
1. Add executable paths to enforceable Landlock rules
2. Gate toPolicy/toDsl on execs similar to connects
3. Reject compilation when execs are non-empty and coverage is incomplete

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3819982841
