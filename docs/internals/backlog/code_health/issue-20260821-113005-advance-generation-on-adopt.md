---
title: "Advance the generation when adopting a new descriptor"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "platform"
target_modules:
  - ":platform"
target_files:
  - "platform/src/main/kotlin/io/mazewall/core/FileDescriptor.kt"
effort: "small"
autonomy: "autonomous"
related_pr: 512
related_thread: 3825912176
---

# 🟡 [Severity: MEDIUM]: Advance the generation when adopting a new descriptor

**Context:** When a tracked descriptor is closed outside the typed wrapper and the kernel later reuses its integer for an `open`, `accept`, `dup`, or `SCM_RIGHTS` result, `adopt()` delegates to `open()`, whose `claimOpen()` returns the existing live generation rather than creating the new generation promised here. A stale token for the old resource is then considered live by this new descriptor, so usage of the new descriptor can be rejected as already closed.

**Problem:**
- Descriptor closed outside wrapper
- Kernel reuses integer for new open/accept/dup
- adopt() delegates to open()
- claimOpen() returns existing live generation
- Stale token considered live

**Impact:**
- New descriptor rejected as already closed
- Functionality: descriptor reuse broken

**Needed:**
1. claimOpen() should create new generation when adopting
2. Advance generation for kernel-reused descriptors
3. Ensure adopt() creates new generation

**Codex PR Comment:** https://github.com/Pilleo/mazewall/pull/512#discussion_r3825912176
